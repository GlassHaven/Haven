// Host-side smoke driver for the same RdpClient code path the Android AAR
// uses. Lets us iterate on EGFX/protocol changes without rebuilding the
// AAR and redeploying to a device. Build with:
//
//   cargo build --features host-cli --bin rdp-cli
//
// Run with:
//
//   RUST_LOG=debug ./target/debug/rdp-cli <host> <port> <user> <pass> [domain] [seconds]
//
// Example:
//
//   RUST_LOG=rdp_transport=debug ./target/debug/rdp-cli \
//       192.168.122.83 3389 Administrator WinniePico '' 30

use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

    use rdp_transport::{
        ClipboardCallback, FrameCallback, RdpAudioMode, RdpClient, RdpConfig, SessionCallback,
    };

struct StderrFrameCb {
    frames: AtomicU64,
    last_log: std::sync::Mutex<Instant>,
}

impl FrameCallback for StderrFrameCb {
    fn on_frame_update(&self, x: u16, y: u16, w: u16, h: u16) {
        let n = self.frames.fetch_add(1, Ordering::Relaxed) + 1;
        let mut last = self.last_log.lock().unwrap();
        if last.elapsed() >= Duration::from_secs(1) {
            eprintln!("[frame] #{n} last={x},{y} {w}x{h}");
            *last = Instant::now();
        }
    }
    fn on_resize(&self, width: u16, height: u16) {
        eprintln!("[resize] {width}x{height}");
    }
}

struct StderrSessionCb {
    connected: Arc<AtomicBool>,
    error: Arc<AtomicBool>,
}

impl SessionCallback for StderrSessionCb {
    fn on_connected(&self, width: u16, height: u16) {
        eprintln!("[connected] {width}x{height}");
        self.connected.store(true, Ordering::Release);
    }
    fn on_error(&self, message: String) {
        eprintln!("[error] {message}");
        self.error.store(true, Ordering::Release);
    }
    fn on_disconnected(&self) {
        eprintln!("[disconnected]");
    }
    fn on_server_cert(&self, sha256: String) {
        eprintln!("[server-cert] sha256={sha256}");
    }
}

struct StderrClipCb;
impl ClipboardCallback for StderrClipCb {
    fn on_remote_clipboard(&self, text: String) {
        eprintln!("[clipboard] {} bytes", text.len());
    }
}

fn main() -> ExitCode {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .format_timestamp_millis()
        .init();

    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!(
            "usage: {} <host> <port> <user> <password> [domain] [seconds]",
            args.get(0).map(String::as_str).unwrap_or("rdp-cli")
        );
        return ExitCode::from(2);
    }
    let host = args[1].clone();
    let port: u16 = match args[2].parse() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("bad port {:?}: {e}", args[2]);
            return ExitCode::from(2);
        }
    };
    let user = args[3].clone();
    let pass = args[4].clone();
    let domain = args.get(5).cloned().unwrap_or_default();
    let seconds: u64 = args.get(6).and_then(|s| s.parse().ok()).unwrap_or(30);
    // Optional 8th arg: a string of unicode chars to type ~1s after connect.
    // Use "\r" / "\n" for Enter, "\b" for backspace. Useful to coax a server
    // into emitting EGFX surface updates instead of just sitting idle.
    let type_after = args.get(7).cloned().unwrap_or_default();

    // RDP_NLA=0 disables CredSSP/NLA (reach the login screen on a permissive
    // server without valid creds — enough to capture an EGFX frame for colour
    // diagnosis). RDP_DEPTH overrides the negotiated colour depth.
    let enable_credssp = std::env::var("RDP_NLA").map(|v| v != "0").unwrap_or(true);
    let color_depth: u8 = std::env::var("RDP_DEPTH").ok().and_then(|s| s.parse().ok()).unwrap_or(32);
    let width: u16 = std::env::var("RDP_W").ok().and_then(|s| s.parse().ok()).unwrap_or(1280);
    let height: u16 = std::env::var("RDP_H").ok().and_then(|s| s.parse().ok()).unwrap_or(800);
    let config = RdpConfig {
        username: user,
        password: pass,
        domain,
        width,
        height,
        color_depth,
        enable_credssp,
        // Host CLI: no persisted pin store; accept-and-report on first use.
        pinned_cert_sha256: std::env::var("RDP_PINNED_CERT").ok(),
        // #418/#496: WBT_TILE_UPGRADE refinement decoding, on by default to
        // match the app. `HAVEN_RFX_UPGRADE=0 rdp-cli …` turns it off, which is
        // how the before/after comparison on #496 was measured.
        progressive_upgrade: std::env::var("HAVEN_RFX_UPGRADE").map(|v| v != "0").unwrap_or(true),
        // #425: advertise AVC420 for KRDP capture — `HAVEN_RDP_AVC=1 rdp-cli …`.
        // No MediaCodec on the host, so tiles are dumped (EGFX_DUMP_DIR) and
        // dropped (no Avc420Decoder registered); capture-only.
        avc_enabled: std::env::var("HAVEN_RDP_AVC").is_ok(),
        keyboard_layout: 0,
        enable_clipboard: true,
        audio_mode: RdpAudioMode::Disabled,
        shared_folder_name: None,
        enable_location: false,
        enable_camera: false,
        enable_microphone: false,
    };

    let client = Arc::new(RdpClient::new(config));

    let connected = Arc::new(AtomicBool::new(false));
    let error = Arc::new(AtomicBool::new(false));

    client.set_frame_callback(Arc::new(StderrFrameCb {
        frames: AtomicU64::new(0),
        last_log: std::sync::Mutex::new(Instant::now() - Duration::from_secs(2)),
    }));
    client.set_session_callback(Arc::new(StderrSessionCb {
        connected: connected.clone(),
        error: error.clone(),
    }));
    client.set_clipboard_callback(Arc::new(StderrClipCb));

    eprintln!("[connecting] {host}:{port}");
    if let Err(e) = client.connect(host, port, None) {
        eprintln!("[connect-failed] {e:?}");
        return ExitCode::from(1);
    }

    // #477: RDP_MOUSE_HZ spawns a thread that sweeps the cursor back and forth
    // at a fixed offered rate, standing in for a finger drag. Pair it with the
    // session loop's "input N events/s" perf line: offered here vs delivered
    // there is exactly the coupling this issue is about. The generator is
    // deliberately on its own thread so the offered rate is independent of
    // anything the session loop is doing.
    if let Ok(hz) = std::env::var("RDP_MOUSE_HZ").map(|s| s.parse::<u64>().unwrap_or(0)) {
        if hz > 0 {
            let c = Arc::clone(&client);
            let conn = connected.clone();
            let period = Duration::from_micros(1_000_000 / hz);
            std::thread::spawn(move || {
                // RDP_MOUSE_STEP=0 parks the cursor: same PDU rate, no
                // server-side pointer motion. Separates client-side lock
                // contention from the server doing more repaint work now that
                // input actually arrives promptly.
                let step: u16 = std::env::var("RDP_MOUSE_STEP")
                    .ok()
                    .and_then(|s| s.parse().ok())
                    .unwrap_or(4);
                let mut x: u16 = 100;
                let mut forward = true;
                let mut sent: u64 = 0;
                let start = Instant::now();
                let mut last_log = Instant::now();
                while conn.load(Ordering::Acquire) || start.elapsed() < Duration::from_secs(10) {
                    if conn.load(Ordering::Acquire) {
                        c.send_mouse_move(x, 300);
                        sent += 1;
                        if forward { x += step } else { x -= step }
                        if x >= 900 { forward = false }
                        if x <= 100 { forward = true }
                        if last_log.elapsed() >= Duration::from_secs(5) {
                            eprintln!(
                                "[mouse] offered {:.1}/s ({sent} total)",
                                sent as f64 / start.elapsed().as_secs_f64()
                            );
                            last_log = Instant::now();
                        }
                    }
                    std::thread::sleep(period);
                }
            });
        }
    }

    // #422: RDP_KEYS = space/comma-separated hex scancodes sent as press +
    // release pairs after connect (0xE000 marker = extended, matching the
    // Kotlin SC_* constants). RDP_KEY_DELAY = seconds to wait first (default
    // 2, the reporter pressed ~30s in). e.g. RDP_KEYS="e050 e050 e050".
    let keys_after: Vec<u16> = std::env::var("RDP_KEYS")
        .map(|s| {
            s.split([' ', ','])
                .filter(|t| !t.is_empty())
                .filter_map(|t| u16::from_str_radix(t.trim_start_matches("0x"), 16).ok())
                .collect()
        })
        .unwrap_or_default();
    let key_delay: u64 = std::env::var("RDP_KEY_DELAY").ok().and_then(|s| s.parse().ok()).unwrap_or(2);

    let deadline = Instant::now() + Duration::from_secs(seconds);
    let mut typed = type_after.is_empty();
    let mut keyed = keys_after.is_empty();
    while Instant::now() < deadline {
        if error.load(Ordering::Acquire) {
            client.disconnect();
            return ExitCode::from(1);
        }
        if !typed && connected.load(Ordering::Acquire) {
            // Wait 2s after the connected callback so the server has time to
            // finish initial paint, then poke each char (unicode press +
            // release, 80 ms apart).
            std::thread::sleep(Duration::from_secs(2));
            for ch in type_after.chars() {
                let cp = ch as u32;
                eprintln!("[type] U+{cp:04x} ({ch:?})");
                client.send_unicode_key(cp, true);
                std::thread::sleep(Duration::from_millis(40));
                client.send_unicode_key(cp, false);
                std::thread::sleep(Duration::from_millis(80));
            }
            typed = true;
        }
        if !keyed && connected.load(Ordering::Acquire) {
            std::thread::sleep(Duration::from_secs(key_delay));
            for &sc in &keys_after {
                eprintln!("[key] {sc:#06x}");
                client.send_key(sc, true);
                std::thread::sleep(Duration::from_millis(40));
                client.send_key(sc, false);
                std::thread::sleep(Duration::from_millis(110));
            }
            keyed = true;
        }
        std::thread::sleep(Duration::from_millis(200));
    }

    // Dump the final framebuffer so we can inspect exactly what the Android
    // viewer would render. The framebuffer bytes are Android ARGB_8888 native
    // LE = [B,G,R,A] per pixel (see update_framebuffer / try_handle_slow_path_
    // bitmap), so emitting RGB=(byte2,byte1,byte0) reproduces the on-device
    // colours faithfully. PPM (P6) keeps it dependency-free. (#212)
    if let Some(fb) = client.get_framebuffer() {
        let w = fb.width as usize;
        let h = fb.height as usize;
        let path = std::env::var("RDP_DUMP").unwrap_or_else(|_| "/tmp/rdp_fb.ppm".into());
        match std::fs::File::create(&path) {
            Ok(mut f) => {
                use std::io::Write;
                let _ = write!(f, "P6\n{w} {h}\n255\n");
                let mut rgb = Vec::with_capacity(w * h * 3);
                let mut nonblack = 0u64;
                for px in fb.pixels.chunks_exact(4) {
                    // Framebuffer is RGBA ([R,G,B,A]) — same as on-device.
                    let (r, g, b) = (px[0], px[1], px[2]);
                    if r != 0 || g != 0 || b != 0 {
                        nonblack += 1;
                    }
                    rgb.extend_from_slice(&[r, g, b]);
                }
                let _ = f.write_all(&rgb);
                let total = (w * h) as u64;
                eprintln!(
                    "[dump] wrote {path} ({w}x{h}); non-black {nonblack}/{total} = {:.1}%",
                    100.0 * nonblack as f64 / total.max(1) as f64
                );
            }
            Err(e) => eprintln!("[dump-failed] {e}"),
        }
    } else {
        eprintln!("[dump] no framebuffer available");
    }

    eprintln!("[deadline] disconnecting");
    client.disconnect();
    if connected.load(Ordering::Acquire) {
        ExitCode::SUCCESS
    } else {
        eprintln!("[never-connected]");
        ExitCode::from(1)
    }
}
