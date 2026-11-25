//! Write decoded frames straight into a locked Android bitmap (#466).
//!
//! The publish path used to hand every frame across the FFI as a byte array:
//! `get_framebuffer_region` built a `Vec`, UniFFI marshalled that into a JVM
//! `ByteArray`, and Kotlin copied it into the bitmap. Three copies of the
//! region — 8.3MB each at 1080p, every frame — to move pixels we already had
//! into a buffer Android was going to hand us anyway. Measured at ~57ms per
//! frame after the easy half was removed, against a ~138ms frame budget.
//!
//! This copies once: framebuffer -> locked bitmap, row by row.
//!
//! It cannot go through UniFFI. `AndroidBitmap_lockPixels` needs the JNIEnv and
//! the jobject, so this is a hand-written JNI entry point — and a raw JNI
//! function has no way to reach a UniFFI object, whose handle is opaque. Hence
//! the registry below: the client registers its state on construction and hands
//! Kotlin an id, which the JNI call uses to find it again.
//!
//! Weak references throughout, so a session that goes away cannot be resurrected
//! by a late call from a bitmap the viewer still holds; the lookup simply fails
//! and the caller falls back to the byte-array path.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock, Weak};

use super::SessionState;

static NEXT_ID: AtomicI64 = AtomicI64::new(1);

type Registry = Mutex<HashMap<i64, Weak<RwLock<SessionState>>>>;

fn registry() -> &'static Registry {
    static R: OnceLock<Registry> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Register a session's state and return the id Kotlin passes back in.
pub(crate) fn register(state: &Arc<RwLock<SessionState>>) -> i64 {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    if let Ok(mut map) = registry().lock() {
        // Drop entries whose session has gone; nothing else prunes this and a
        // long-lived process would otherwise accumulate dead weak refs.
        map.retain(|_, w| w.strong_count() > 0);
        map.insert(id, Arc::downgrade(state));
    }
    id
}

pub(crate) fn unregister(id: i64) {
    if let Ok(mut map) = registry().lock() {
        map.remove(&id);
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn lookup(id: i64) -> Option<Arc<RwLock<SessionState>>> {
    registry().lock().ok()?.get(&id)?.upgrade()
}

#[cfg(target_os = "android")]
mod android {
    use super::lookup;
    use std::os::raw::{c_int, c_void};
    use std::ptr;

    /// Mirrors `AndroidBitmapInfo` from <android/bitmap.h>.
    #[repr(C)]
    #[derive(Default)]
    struct AndroidBitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: c_int,
        flags: u32,
    }

    const ANDROID_BITMAP_FORMAT_RGBA_8888: c_int = 1;
    const ANDROID_BITMAP_RESULT_SUCCESS: c_int = 0;

    #[link(name = "jnigraphics")]
    extern "C" {
        fn AndroidBitmap_getInfo(
            env: *mut c_void,
            jbitmap: *mut c_void,
            info: *mut AndroidBitmapInfo,
        ) -> c_int;
        fn AndroidBitmap_lockPixels(
            env: *mut c_void,
            jbitmap: *mut c_void,
            addr: *mut *mut c_void,
        ) -> c_int;
        fn AndroidBitmap_unlockPixels(env: *mut c_void, jbitmap: *mut c_void) -> c_int;
    }

    /// `RdpBitmapBridge.blitRegion` — copy a framebuffer region into `bitmap`.
    ///
    /// Returns 0 for every failure the caller can recover from (unknown id,
    /// wrong format, size mismatch, lock refused). Kotlin falls back to the
    /// byte-array path on 0, so a refusal costs a frame's latency and never
    /// correctness.
    ///
    /// # Safety
    /// Called only from the JVM with a live JNIEnv and a Bitmap it owns.
    #[no_mangle]
    pub unsafe extern "C" fn Java_sh_haven_core_rdp_RdpBitmapBridge_blitRegion(
        env: *mut c_void,
        _class: *mut c_void,
        bitmap: *mut c_void,
        bridge_id: i64,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
    ) -> u8 {
        if env.is_null() || bitmap.is_null() || x < 0 || y < 0 || w <= 0 || h <= 0 {
            return 0;
        }
        let Some(state) = lookup(bridge_id) else {
            return 0;
        };
        let Ok(guard) = state.read() else {
            return 0;
        };
        let Some(fb) = guard.framebuffer.as_ref() else {
            return 0;
        };

        let fb_w = fb.width as usize;
        let fb_h = fb.height as usize;
        if fb_w == 0 || fb_h == 0 || fb.pixels.len() < fb_w * fb_h * 4 {
            return 0;
        }

        let mut info = AndroidBitmapInfo::default();
        if AndroidBitmap_getInfo(env, bitmap, &mut info) != ANDROID_BITMAP_RESULT_SUCCESS {
            return 0;
        }
        // Only the format our framebuffer already matches byte-for-byte. The
        // existing copyPixelsFromBuffer path relies on the same equivalence
        // (RgbA32 in, ARGB_8888 out, no swizzle), so this is not a new
        // assumption — just one that must now be checked explicitly.
        if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
            return 0;
        }
        // A bitmap of a different size than the framebuffer means the viewer is
        // mid-resize; let the slow path deal with it rather than writing into
        // the wrong geometry.
        if info.width as usize != fb_w || info.height as usize != fb_h {
            return 0;
        }

        let x = x as usize;
        let y = y as usize;
        if x >= fb_w || y >= fb_h {
            return 0;
        }
        let w = (w as usize).min(fb_w - x);
        let h = (h as usize).min(fb_h - y);
        if w == 0 || h == 0 {
            return 0;
        }

        let dst_stride = info.stride as usize;
        let row_bytes = w * 4;
        if dst_stride < fb_w * 4 {
            return 0;
        }

        let mut addr: *mut c_void = ptr::null_mut();
        if AndroidBitmap_lockPixels(env, bitmap, &mut addr) != ANDROID_BITMAP_RESULT_SUCCESS
            || addr.is_null()
        {
            return 0;
        }

        let src_stride = fb_w * 4;
        let dst = addr as *mut u8;
        let src = fb.pixels.as_ptr();
        for row in 0..h {
            let src_off = (y + row) * src_stride + x * 4;
            let dst_off = (y + row) * dst_stride + x * 4;
            // Bounds are established above: src by the pixels.len() check, dst
            // by stride >= width*4 and the height match.
            ptr::copy_nonoverlapping(src.add(src_off), dst.add(dst_off), row_bytes);
        }

        AndroidBitmap_unlockPixels(env, bitmap);
        1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn a_state() -> Arc<RwLock<SessionState>> {
        Arc::new(RwLock::new(SessionState {
            connected: false,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            audio_callback: None,
            drive_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }))
    }

    #[test]
    fn an_id_finds_its_own_session_and_no_other() {
        let a = a_state();
        let b = a_state();
        let id_a = register(&a);
        let id_b = register(&b);
        assert_ne!(id_a, id_b, "ids must be distinct or one session writes into another's bitmap");
        assert!(Arc::ptr_eq(&lookup(id_a).expect("a"), &a));
        assert!(Arc::ptr_eq(&lookup(id_b).expect("b"), &b));
        unregister(id_a);
        unregister(id_b);
    }

    /// The lookup holds a Weak on purpose: a bitmap the viewer still owns can
    /// outlive its session, and a late blit must fail rather than resurrect a
    /// closed session's state.
    #[test]
    fn a_dropped_session_stops_resolving() {
        let id = {
            let s = a_state();
            let id = register(&s);
            assert!(lookup(id).is_some(), "should resolve while alive");
            id
        };
        assert!(lookup(id).is_none(), "a dropped session must not resolve");
        unregister(id);
    }

    #[test]
    fn unregister_makes_the_id_dead() {
        let s = a_state();
        let id = register(&s);
        unregister(id);
        assert!(lookup(id).is_none());
    }
}
