//! EGFX (MS-RDPEGFX) client over DRDYNVC.
//!
//! Phase 3a scope: surface + cache management, SolidFill, frame ACKs.
//! Codec-decoded WireToSurface tiles land in 3b — without codec decode the
//! cache stays empty so cache replays are no-ops, but everything else works.
//!
//! Channel name: "Microsoft::Windows::RDS::Graphics".

use std::sync::{Arc, RwLock};

use ironrdp_core::{impl_as_any, Decode as _, Encode, EncodeResult, ReadCursor, WriteCursor};
use ironrdp_dvc::{DvcClientProcessor, DvcEncode, DvcMessage, DvcProcessor};
use ironrdp_graphics::zgfx::Decompressor;
use ironrdp_egfx::pdu::{
    Avc420BitmapStream, CapabilitiesAdvertisePdu, CapabilitiesV10Flags, CapabilitiesV81Flags, CapabilitySet, GfxPdu, Codec1Type, Codec2Type, FrameAcknowledgePdu, PixelFormat, QueueDepth, WireToSurface1Pdu, WireToSurface2Pdu,
};
use ironrdp_pdu::PduResult;
use log::{debug, info, warn};

mod clear;
mod progressive;
mod surface;

use clear::ClearDecoder;
use progressive::ProgressiveDecoder;
use surface::SurfaceManager;

use crate::SessionState;

const CHANNEL_NAME: &str = "Microsoft::Windows::RDS::Graphics";

/// Wrapper so we can implement [`DvcEncode`] for upstream's [`GfxPdu`].
struct GfxClientMessage(GfxPdu);

impl Encode for GfxClientMessage {
    fn encode(&self, dst: &mut WriteCursor<'_>) -> EncodeResult<()> {
        self.0.encode(dst)
    }
    fn name(&self) -> &'static str {
        "GfxClientMessage"
    }
    fn size(&self) -> usize {
        self.0.size()
    }
}

impl DvcEncode for GfxClientMessage {}

/// EGFX processor: caps, frame ACKs, server-PDU logging.
///
/// Surface management and codec decoding land in egfx::surface and
/// egfx::rfx (Phase 3). For now we ACK every frame so the server doesn't
/// throttle at `max_unacknowledged_frame_count` (FreeRDP-style: queue_depth=0
/// means "no backlog, please send the next frame").
/// Per-frame cost breakdown for the EGFX display path (#466).
///
/// The first attempt at this timed `ActiveStageOutput::GraphicsUpdate` in the
/// session loop — which EGFX never emits. It publishes from its own
/// [`EgfxProcessor::flush_dirty_to_framebuffer`], so a whole session produced
/// zero timing lines. Measured on a live session before shipping, which is the
/// only reason that was caught; a reporter would have sent back an empty log.
#[derive(Default)]
pub(crate) struct EgfxPerf {
    frames: u64,
    /// Codec decode: progressive/ClearCodec/planar tiles, or the MediaCodec
    /// round-trip plus YUV->RGB for AVC.
    decode_us: u64,
    /// Publishing the frame: dirty-rect copy plus the callback into the app.
    flush_us: u64,
    zgfx_us: u64,
    /// The AVC decoder round trip as Rust sees it: the host decode plus the
    /// UniFFI marshalling of the returned frame. The host reports its own
    /// internal split separately, and the two did not agree — 107ms inside the
    /// Kotlin decoder against 309ms for the whole dispatch at 2560x1440 (#477).
    /// Splitting the round trip from the blit is what says which of the two
    /// carries that ~200ms, instead of it being attributed by guesswork.
    avc_call_us: u64,
    /// A yardstick for the line above: what it costs *this device* to allocate
    /// and copy the same number of bytes the round trip carries back. The
    /// round trip has consistently measured far more than the work the host
    /// reports doing inside it, and the two candidate explanations — copying
    /// the frame across the boundary, or fixed dispatch overhead — predict
    /// very different values here. Measured once per report window.
    memcpy_us: u64,
    memcpy_kb: u64,
    /// `blit_rgba` of the decoded frame into the surface.
    blit_us: u64,
    /// I420 → RGBA, which the host used to do before returning (#466).
    /// Reported separately so moving it here can be judged rather than
    /// assumed: it should be a fraction of the 27-109ms it cost in Kotlin,
    /// and if it is not, that is worth knowing immediately.
    yuv_us: u64,
    since_report: Option<std::time::Instant>,
}

const EGFX_PERF_REPORT_FRAMES: u64 = 30;

impl EgfxPerf {
    /// `state` is where the line is parked for the host to drain (#477). It
    /// went only to the Android log before, which needs adb — so the number
    /// that says whether decode is the bottleneck could not reach the app's
    /// own verbose log, and reporters had no way to send it.
    fn maybe_report(&mut self, state: &Arc<RwLock<SessionState>>) {
        let started = *self.since_report.get_or_insert_with(std::time::Instant::now);
        if self.frames < EGFX_PERF_REPORT_FRAMES {
            return;
        }
        let secs = started.elapsed().as_secs_f64().max(0.000_001);
        let n = self.frames;
        // The flush runs inside EndFrame's dispatch, which the per-PDU timer
        // also wraps — so decode_us contains flush_us. Subtract, or the two
        // numbers overlap and "decode" looks worse than it is.
        let decode_only = self.decode_us.saturating_sub(self.flush_us);
        let line = format!(
            "EGFX perf: {:.1} fps over {n} frames — per frame: zgfx {}us, decode {}us \
             (avc round trip {}us, yuv {}us, blit {}us), publish {}us, total {}us{}",
            n as f64 / secs,
            self.zgfx_us / n,
            decode_only / n,
            self.avc_call_us / n,
            self.yuv_us / n,
            self.blit_us / n,
            self.flush_us / n,
            (self.zgfx_us + self.decode_us) / n,
            // Only when the probe actually ran. A session the server never
            // sends H.264 to — Windows uses progressive for ordinary desktop
            // updates even with AVC420 negotiated — leaves it at zero, and
            // "alloc+copy of 0KB took 0us" reads like a measurement saying
            // copying is free rather than a probe that never fired. Caught by
            // running it against a real Windows 11 server (#466/#477).
            if self.memcpy_us > 0 {
                format!(
                    ", yardstick: alloc+copy of {}KB took {}us",
                    self.memcpy_kb, self.memcpy_us,
                )
            } else {
                String::new()
            },
        );
        info!("{line}");
        if let Ok(mut s) = state.write() {
            s.push_perf(line);
        }
        *self = Self::default();
    }
}

pub struct EgfxProcessor {
    state: Arc<RwLock<SessionState>>,
    pub(crate) perf: EgfxPerf,
    capabilities_received: bool,
    server_pdu_count: u64,
    /// MS-RDPEGFX wraps every DVC payload in an RDP_SEGMENTED_DATA PDU
    /// with ZGFX (RDP 8.0) bulk compression. The decompressor keeps a
    /// 2.5 MB sliding history shared across the whole channel lifetime.
    zgfx: Decompressor,
    /// Total EndFrame count we've seen — included in every FrameAck so
    /// the server can correlate decode progress.
    total_frames_decoded: u32,
    surfaces: SurfaceManager,
    /// ClearCodec context (sequence counter, glyph + vbar caches). The
    /// decoder is per-channel, not per-surface — the spec requires the
    /// caches to survive `ResetGraphics`.
    clear_decoder: ClearDecoder,
    /// RemoteFxProgressive context (sync state, context flags, IDWT
    /// scratch buffers). Per-channel, survives across PDUs.
    progressive_decoder: ProgressiveDecoder,
    /// RDP 6.0 planar bitmap decoder (xrdp encodes greeter/session tiles
    /// as Codec1Type::Planar). Holds a reusable planes buffer.
    planar_decoder: ironrdp_graphics::rdp6::BitmapStreamDecoder,
    /// #425: advertise H.264/AVC420 support (KRDP). Decode itself is done by
    /// the host-registered [`crate::Avc420Decoder`] (MediaCodec on Android),
    /// reached through `state.avc_decoder`.
    avc_enabled: bool,
    /// Reused RGBA target for [`crate::yuv::i420_to_rgba`]. Held across frames
    /// because allocating 8.29MB per frame at 1080p is the churn this whole
    /// change exists to remove.
    avc_rgba: Vec<u8>,
    /// Reused I420 target the host decoder writes into. Held across frames for
    /// the same reason as [`Self::avc_rgba`], and additionally because its
    /// address is handed to foreign code for the duration of each call — a
    /// buffer reallocated per frame would be a fresh address every time and
    /// give the contract on `decode_into` nothing stable to rest on.
    avc_i420: Vec<u8>,
}

impl EgfxProcessor {
    /// `progressive_upgrade` enables WBT_TILE_UPGRADE refinement decoding
    /// (#418) — a hidden/debug opt-in while the upgrade path is verified
    /// against real Windows captures. Default path passes `false`.
    pub fn new(state: Arc<RwLock<SessionState>>, progressive_upgrade: bool, avc_enabled: bool) -> Self {
        let mut progressive_decoder = ProgressiveDecoder::new();
        progressive_decoder.set_upgrade_enabled(progressive_upgrade);
        Self {
            state,
            perf: EgfxPerf::default(),
            capabilities_received: false,
            server_pdu_count: 0,
            zgfx: Decompressor::new(),
            total_frames_decoded: 0,
            surfaces: SurfaceManager::new(),
            clear_decoder: ClearDecoder::new(),
            progressive_decoder,
            planar_decoder: ironrdp_graphics::rdp6::BitmapStreamDecoder::default(),
            avc_enabled,
            avc_rgba: Vec::new(),
            avc_i420: Vec::new(),
        }
    }
}

impl_as_any!(EgfxProcessor);

impl DvcProcessor for EgfxProcessor {
    fn channel_name(&self) -> &str {
        CHANNEL_NAME
    }

    /// Sent immediately after the DVC is created. Advertise V10 with
    /// `AVC_DISABLED` so the server picks ClearCodec / RemoteFX-Progressive /
    /// classic RemoteFX over AVC. Codec-version-only restriction (e.g.
    /// advertising V8) does *not* limit the server to classic RemoteFX —
    /// codec selection is per-tile by content type, independent of cap
    /// version, so Windows still emits ClearCodec for desktop UI either way.
    fn start(&mut self, _channel_id: u32) -> PduResult<Vec<DvcMessage>> {
        // #425: advertising AVC420_ENABLED lets an H.264-only server (KRDP)
        // drive the session. Gated on `RdpConfig.avc_enabled` (threaded in via
        // `EgfxProcessor::new`), which requires the host to have registered an
        // `Avc420Decoder` (MediaCodec on Android) — else negotiated AVC tiles
        // are dropped and the screen stays black. The Android app enables it by
        // default (KRDP-verified); `HAVEN_RDP_AVC=1` is an additional OR for the
        // host `rdp-cli` capture harness. When off → V10 AVC_DISABLED (server
        // picks ClearCodec / RemoteFX-Progressive).
        let avc = self.avc_enabled || std::env::var("HAVEN_RDP_AVC").is_ok();
        let caps = if avc {
            // Advertise ONLY V8.1 with AVC420_ENABLED. KRDP (FreeRDP server) only
            // encodes AVC420/YUV420, gated on the V8.1 AVC420_ENABLED flag, and
            // FreeRDP always *selects the highest advertised version* — so adding
            // V10 makes it pick V10 (which it reads as "YUV420 false") and it then
            // has nothing to send. V8.1-only forces the YUV420 path. AVC444 (V10)
            // is a later slice once we decode it. #425.
            //
            // SMALL_CACHE is not optional here (#477). A Windows 11 24H2 server
            // *closes the graphics channel* when V8.1 arrives with
            // AVC420_ENABLED as its only flag, and the session then falls back
            // to legacy fast-path bitmaps for its whole life. Measured against
            // win11-rdptest, same binary, one bit apart, twice each:
            //
            //   flags                          | Windows
            //   0x10 AVC420_ENABLED alone      | channel closed, 0 EGFX frames
            //   0x12 + SMALL_CACHE             | confirms V8.1
            //   0x11 + THIN_CLIENT             | confirms V8.1
            //   0x02 SMALL_CACHE alone         | confirms V8.1
            //   0x00 no flags                  | confirms V8.1
            //
            // Why the lone AVC bit is the one Windows refuses is unexplained —
            // the encoding is byte-identical apart from that word. SMALL_CACHE
            // is the flag to add regardless of that: it asks for the 16MB cache
            // profile rather than 100MB, which is what a phone should want, and
            // `SurfaceManager`'s cache is an unbounded-by-bytes HashMap.
            // FreeRDP is unaffected — it selects purely on capset *version* and
            // gates AVC420 on the AVC420_ENABLED bit alone (shadow_client.c
            // `shadow_client_rdpgfx_caps_advertise` / `shadow_avc420_enabled`),
            // confirmed on the wire against freerdp-shadow-cli.
            info!("EGFX: sending CapabilitiesAdvertise(V8_1 AVC420_ENABLED|SMALL_CACHE)");
            CapabilitiesAdvertisePdu::from_typed(&[CapabilitySet::V8_1 {
                flags: CapabilitiesV81Flags::AVC420_ENABLED | CapabilitiesV81Flags::SMALL_CACHE,
            }])
        } else {
            info!("EGFX: sending CapabilitiesAdvertise(V10, AVC_DISABLED)");
            CapabilitiesAdvertisePdu::from_typed(&[CapabilitySet::V10 {
                flags: CapabilitiesV10Flags::AVC_DISABLED,
            }])
        };
        let msg: DvcMessage = Box::new(GfxClientMessage(GfxPdu::CapabilitiesAdvertise(caps)));
        Ok(vec![msg])
    }

    fn process(&mut self, _channel_id: u32, payload: &[u8]) -> PduResult<Vec<DvcMessage>> {
        // Step 1: ZGFX decompress (every EGFX wire payload is wrapped).
        let mut decompressed = Vec::with_capacity(payload.len() * 4);
        let t_zgfx = std::time::Instant::now();
        if let Err(e) = self.zgfx.decompress(payload, &mut decompressed) {
            warn!(
                "EGFX zgfx decompress failed ({e:?}); skipping {} byte payload",
                payload.len()
            );
            return Ok(Vec::new());
        }
        self.perf.zgfx_us += t_zgfx.elapsed().as_micros() as u64;
        debug!(
            "EGFX zgfx in={} out={} (ratio {:.2}x)",
            payload.len(),
            decompressed.len(),
            decompressed.len() as f32 / payload.len().max(1) as f32
        );
        // Step 2: decode every concatenated GfxPdu in the buffer. A single
        // DVC message often carries StartFrame / WireToSurface* / EndFrame
        // back-to-back for one surface update.
        let mut out_messages: Vec<DvcMessage> = Vec::new();
        let mut cur = ReadCursor::new(&decompressed);
        while !cur.is_empty() {
            self.server_pdu_count = self.server_pdu_count.saturating_add(1);
            let n = self.server_pdu_count;
            let pdu_start = cur.pos();
            let t_pdu = std::time::Instant::now();
            let pdu = match <GfxPdu as ironrdp_core::Decode>::decode(&mut cur) {
                Ok(p) => p,
                Err(e) => {
                    warn!(
                        "EGFX[{n}]: decode failed ({e}); {} bytes remaining",
                        cur.len()
                    );
                    break;
                }
            };
            let pdu_end = cur.pos();
            maybe_dump_pdu(n, &decompressed[pdu_start..pdu_end], &pdu);
            let is_end_frame = matches!(pdu, GfxPdu::EndFrame(_));
            self.dispatch(n, &pdu, &mut out_messages);
            // Everything a PDU costs: wire decode plus, for surface PDUs, the
            // codec work. EndFrame's own cost is the flush, counted separately
            // inside flush_dirty_to_framebuffer.
            self.perf.decode_us += t_pdu.elapsed().as_micros() as u64;
            if is_end_frame {
                self.perf.frames += 1;
                // Disjoint field borrows: &mut self.perf and &self.state.
                self.perf.maybe_report(&self.state);
            }
        }
        Ok(out_messages)
    }
}

impl EgfxProcessor {
    /// Inspect a single decoded server PDU. Push any client-side reply
    /// (frame ack, etc.) into `out`.
    fn dispatch(&mut self, n: u64, pdu: &GfxPdu, out: &mut Vec<DvcMessage>) {
        match pdu {
            GfxPdu::CapabilitiesConfirm(c) => {
                self.capabilities_received = true;
                info!("EGFX[{n}]: CapabilitiesConfirm {:?}", c.0);
            }
            GfxPdu::ResetGraphics(p) => {
                info!(
                    "EGFX[{n}]: ResetGraphics width={} height={} monitors={}",
                    p.width,
                    p.height,
                    p.monitors.len()
                );
                self.surfaces.reset();
                // #496: refinement state is per-tile and sized by surface area;
                // a rebuilt graphics context invalidates all of it.
                self.progressive_decoder.forget_all();
                self.resize_framebuffer(p.width, p.height);
            }
            GfxPdu::CreateSurface(p) => {
                debug!(
                    "EGFX[{n}]: CreateSurface id={} {}x{} pixfmt={:?}",
                    p.surface_id, p.width, p.height, p.pixel_format
                );
                self.surfaces.create_surface(p);
            }
            GfxPdu::DeleteSurface(p) => {
                debug!("EGFX[{n}]: DeleteSurface id={}", p.surface_id);
                self.surfaces.delete_surface(p);
                // #496: ~48 KB per 64x64 tile of that surface, and nothing else
                // evicts it. Must go with the surface, not linger for the
                // lifetime of the connection.
                self.progressive_decoder.forget_surface(p.surface_id);
            }
            GfxPdu::MapSurfaceToOutput(p) => {
                debug!(
                    "EGFX[{n}]: MapSurfaceToOutput id={} ->({},{})",
                    p.surface_id, p.output_origin_x, p.output_origin_y
                );
                self.surfaces
                    .map_to_output(p.surface_id, p.output_origin_x as i32, p.output_origin_y as i32);
            }
            GfxPdu::StartFrame(p) => debug!(
                "EGFX[{n}]: StartFrame frame_id={} timestamp={:?}",
                p.frame_id, p.timestamp
            ),
            GfxPdu::EndFrame(p) => {
                self.total_frames_decoded = self.total_frames_decoded.saturating_add(1);
                debug!(
                    "EGFX[{n}]: EndFrame frame_id={} total_decoded={}",
                    p.frame_id, self.total_frames_decoded
                );
                self.flush_dirty_to_framebuffer();
                self.maybe_dump_surface(p.frame_id);
                let ack = FrameAcknowledgePdu {
                    queue_depth: QueueDepth::Unavailable, // FreeRDP-equivalent of "send the next frame"
                    frame_id: p.frame_id,
                    total_frames_decoded: self.total_frames_decoded,
                };
                out.push(Box::new(GfxClientMessage(GfxPdu::FrameAcknowledge(ack))));
            }
            GfxPdu::WireToSurface1(p) => self.handle_wire_to_surface1(n, p),
            GfxPdu::WireToSurface2(p) => self.handle_wire_to_surface2(n, p),
            GfxPdu::SolidFill(p) => {
                debug!(
                    "EGFX[{n}]: SolidFill surface={} rects={} colour={:?}",
                    p.surface_id,
                    p.rectangles.len(),
                    p.fill_pixel
                );
                self.surfaces.solid_fill(p);
            }
            GfxPdu::SurfaceToSurface(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToSurface src={} dst={} points={}",
                    p.source_surface_id,
                    p.destination_surface_id,
                    p.destination_points.len()
                );
                self.surfaces.surface_to_surface(p);
            }
            GfxPdu::SurfaceToCache(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToCache surface={} key=0x{:016x} cache_slot={}",
                    p.surface_id, p.cache_key, p.cache_slot
                );
                self.surfaces.surface_to_cache(p);
            }
            GfxPdu::CacheToSurface(p) => {
                debug!(
                    "EGFX[{n}]: CacheToSurface cache_slot={} surface={} positions={}",
                    p.cache_slot,
                    p.surface_id,
                    p.destination_points.len()
                );
                self.surfaces.cache_to_surface(p);
            }
            GfxPdu::EvictCacheEntry(p) => {
                debug!("EGFX[{n}]: EvictCacheEntry cache_slot={}", p.cache_slot);
                self.surfaces.evict_cache(p);
            }
            GfxPdu::DeleteEncodingContext(_) => debug!("EGFX[{n}]: DeleteEncodingContext"),
            GfxPdu::CacheImportReply(_) => debug!("EGFX[{n}]: CacheImportReply"),
            GfxPdu::MapSurfaceToScaledOutput(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledOutput")
            }
            GfxPdu::MapSurfaceToScaledWindow(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledWindow")
            }
            // Client-origin variants of the merged GfxPdu enum
            // (CapabilitiesAdvertise, FrameAcknowledge, CacheImportOffer,
            // QoeFrameAcknowledge, MapSurfaceToWindow): a server must not
            // send these; log and drop.
            other => warn!("EGFX[{n}]: unexpected client-origin PDU: {}", pdu_kind_label(other)),
        }
        if !self.capabilities_received {
            warn!("EGFX[{n}]: server PDU before CapabilitiesConfirm");
        }
    }
}

impl EgfxProcessor {
    /// #474/#467: portal-mirroring servers (KRDP) echo the client's requested
    /// size in Demand Active but stream the physical monitor over EGFX —
    /// `ResetGraphics` carries the real output size. Reallocate the
    /// framebuffer to match, else every blit past the old bounds is clipped
    /// and a 2560x1440/4K desktop renders top-left-only.
    fn resize_framebuffer(&self, width: u32, height: u32) {
        // RDPGFX_RESET_GRAPHICS_PDU caps both at 32766, so u16 holds.
        let (width, height) = (width as u16, height as u16);
        let resize_cb = {
            let Ok(mut s) = self.state.write() else { return };
            if let Some(fb) = s.framebuffer.as_ref() {
                if fb.width == width && fb.height == height {
                    return;
                }
            }
            s.framebuffer = Some(crate::FrameData {
                width,
                height,
                pixels: vec![0u8; width as usize * height as usize * 4],
            });
            s.frame_callback.clone()
        };
        // Outside the lock — Kotlin handlers may call back into the client.
        if let Some(cb) = resize_cb {
            cb.on_resize(width, height);
        }
    }

    /// Drain dirty rects from `SurfaceManager`, project each through the
    /// surface's `MapSurfaceToOutput` mapping, and copy the corresponding
    /// pixels from the surface (RGBA8888) into `SessionState.framebuffer`
    /// (BGRA in memory, i.e. Android `ARGB_8888` little-endian). Coalesces
    /// all rects into a single bounding-box `on_frame_update` call so the
    /// Kotlin/Compose side gets one repaint per frame instead of dozens.
    fn flush_dirty_to_framebuffer(&mut self) {
        let dirty = self.surfaces.take_dirty();
        if dirty.is_empty() {
            return;
        }
        // Project to host-output coords + collect (left, top, w, h) per
        // rect for the copy step. We do the lookups up-front so the
        // SessionState write lock is held only for the actual blit.
        struct ProjectedRect {
            surface_id: u16,
            // surface-local bounds (clipped to surface)
            sx: u32,
            sy: u32,
            w: u32,
            h: u32,
            // host-output bounds (after MapSurfaceToOutput translation)
            ox: i32,
            oy: i32,
        }
        let mut projected: Vec<ProjectedRect> = Vec::with_capacity(dirty.len());
        for (sid, r) in &dirty {
            let Some(surface) = self.surfaces.surface(*sid) else {
                continue;
            };
            let (sx, sy, w, h) = clip_to_surface(r, surface.width, surface.height);
            if w == 0 || h == 0 {
                continue;
            }
            let mapping = self.surfaces.output_for(*sid);
            let (ox, oy) = match mapping {
                Some(m) => (
                    m.output_origin_x + sx as i32,
                    m.output_origin_y + sy as i32,
                ),
                None => (sx as i32, sy as i32),
            };
            projected.push(ProjectedRect {
                surface_id: *sid,
                sx,
                sy,
                w,
                h,
                ox,
                oy,
            });
        }
        if projected.is_empty() {
            return;
        }

        // Bounding box across all rects (in output coords) for the callback.
        let mut bb_l = i32::MAX;
        let mut bb_t = i32::MAX;
        let mut bb_r = i32::MIN;
        let mut bb_b = i32::MIN;

        let state = self.state.clone();
        let frame_cb = {
            let mut s = match state.write() {
                Ok(s) => s,
                Err(_) => return,
            };
            let Some(fb) = s.framebuffer.as_mut() else {
                return;
            };
            let fb_w = fb.width as i32;
            let fb_h = fb.height as i32;
            for pr in &projected {
                let Some(surface) = self.surfaces.surface(pr.surface_id) else {
                    continue;
                };
                // Clip to framebuffer bounds.
                let dst_l = pr.ox.max(0);
                let dst_t = pr.oy.max(0);
                let dst_r = (pr.ox + pr.w as i32).min(fb_w);
                let dst_b = (pr.oy + pr.h as i32).min(fb_h);
                if dst_r <= dst_l || dst_b <= dst_t {
                    continue;
                }
                let copy_w = (dst_r - dst_l) as usize;
                let copy_h = (dst_b - dst_t) as usize;
                let src_x = (pr.sx as i32 + (dst_l - pr.ox)) as usize;
                let src_y = (pr.sy as i32 + (dst_t - pr.oy)) as usize;
                let src_stride = surface.width as usize * 4;
                let dst_stride = fb.width as usize * 4;
                for row in 0..copy_h {
                    let s_off = (src_y + row) * src_stride + src_x * 4;
                    let d_off = (dst_t as usize + row) * dst_stride + dst_l as usize * 4;
                    // Surface is RGBA8888 ([R,G,B,A] bytes). Android's
                    // ARGB_8888 framebuffer (copyPixelsFromBuffer) also expects
                    // RGBA byte order, so copy straight through — no R<->B swap
                    // (#212: the swap rendered blue as orange on-device).
                    let src_row = &surface.pixels[s_off..s_off + copy_w * 4];
                    let dst_row = &mut fb.pixels[d_off..d_off + copy_w * 4];
                    dst_row.copy_from_slice(src_row);
                }
                bb_l = bb_l.min(dst_l);
                bb_t = bb_t.min(dst_t);
                bb_r = bb_r.max(dst_r);
                bb_b = bb_b.max(dst_b);
            }
            s.frame_callback.clone()
        };
        if bb_r <= bb_l || bb_b <= bb_t {
            return;
        }
        debug!(
            "EGFX flush: {} dirty rect(s) -> bbox ({bb_l},{bb_t})-({bb_r},{bb_b})",
            dirty.len()
        );
        if let Some(cb) = frame_cb {
            let t_cb = std::time::Instant::now();
            cb.on_frame_update(
                bb_l as u16,
                bb_t as u16,
                (bb_r - bb_l) as u16,
                (bb_b - bb_t) as u16,
            );
            self.perf.flush_us += t_cb.elapsed().as_micros() as u64;
        }
    }

    /// If `EGFX_DUMP_DIR` is set, write every live surface as a PPM after each
    /// EndFrame. Useful for visual diff against a reference shot of the source
    /// display — no extra image-crate dependency.
    ///
    /// ★ #496: this used to dump surface **0** only, and returned silently
    /// when there wasn't one. FreeRDP's shadow server allocates surface id 1,
    /// so the whole visual-diff path produced nothing at all — tile payloads
    /// appeared in the dump directory but never a rendered frame, with no hint
    /// as to why. Whichever ids the server picked now get dumped.
    fn maybe_dump_surface(&self, frame_id: u32) {
        let Ok(dir) = std::env::var("EGFX_DUMP_DIR") else {
            return;
        };
        let surfaces = self.surfaces.all_surfaces();
        if surfaces.is_empty() {
            warn!("EGFX[{frame_id}]: surface dump requested but no surface exists yet");
            return;
        }
        for (id, s) in surfaces {
            let path = format!("{dir}/surface{id}_frame{frame_id:04}.ppm");
            let mut buf = format!("P6\n{} {}\n255\n", s.width, s.height).into_bytes();
            // Surface stores RGBA8888; PPM is RGB.
            buf.reserve(s.pixels.len() / 4 * 3);
            for px in s.pixels.chunks_exact(4) {
                buf.extend_from_slice(&px[..3]);
            }
            if let Err(e) = std::fs::write(&path, &buf) {
                warn!("EGFX surface dump to {path} failed: {e}");
            } else {
                info!("EGFX surface dumped to {path}");
            }
        }
    }

    fn handle_wire_to_surface1(&mut self, n: u64, p: &WireToSurface1Pdu) {
        // MS-RDPEGFX `RDPGFX_RECT16` uses *exclusive* right/bottom for
        // WireToSurface destinations (matches FreeRDP's `width = right -
        // left`); egfx 0.2 types this correctly as ExclusiveRectangle, so
        // right/bottom are one-past-end and we use the width directly.
        let r = &p.destination_rectangle;
        let w = (r.right as i32 - r.left as i32).max(0) as u32;
        let h = (r.bottom as i32 - r.top as i32).max(0) as u32;
        debug!(
            "EGFX[{n}]: WireToSurface1 surface={} codec={:?} pf={:?} {}x{} @({},{}) {} bytes",
            p.surface_id,
            p.codec_id,
            p.pixel_format,
            w,
            h,
            r.left,
            r.top,
            p.bitmap_data.len()
        );
        if w == 0 || h == 0 {
            return;
        }
        match p.codec_id {
            Codec1Type::ClearCodec => {
                let tile = match self.clear_decoder.decompress(&p.bitmap_data, w, h) {
                    Ok(t) => t,
                    Err(e) => {
                        warn!(
                            "EGFX[{n}]: ClearCodec decompress failed: {e} ({w}x{h}, {} bytes)",
                            p.bitmap_data.len()
                        );
                        // For triage of future regressions, dumping the
                        // payload to /tmp under EGFX_DUMP_DIR matches the
                        // surface-dump convention.
                        if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                            let path = format!("{dir}/clear_fail_{n}_{w}x{h}.bin");
                            let _ = std::fs::write(&path, &p.bitmap_data);
                        }
                        return;
                    }
                };
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &tile);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Uncompressed => {
                // #462: RDPGFX_CODECID_UNCOMPRESSED is the baseline every EGFX
                // server may fall back to for any region — MS-RDPEGFX 2.2.4.1
                // calls it the trivially-correct encoding. Haven dropped it,
                // and a dropped region is an axis-aligned rectangle that never
                // paints: a missing chunk of a glyph run, a notch out of an
                // avatar, a black square on the desktop.
                //
                // The payload is raw rows of the PDU's pixel format, top-down,
                // no padding. Both formats are 32bpp little-endian BGRX/BGRA,
                // so the channel order is B,G,R,(A) on the wire; surfaces hold
                // RGBA, hence the swap. XRgb has no meaningful alpha byte, so
                // it is forced opaque rather than trusted (#212 is the standing
                // lesson on getting this order wrong).
                let want = (w * h * 4) as usize;
                if p.bitmap_data.len() < want {
                    warn!(
                        "EGFX[{n}]: Uncompressed tile too short: {} bytes for {w}x{h} (need {want})",
                        p.bitmap_data.len()
                    );
                    return;
                }
                let opaque = matches!(p.pixel_format, PixelFormat::XRgb);
                let mut tile = Vec::with_capacity(want);
                for px in p.bitmap_data[..want].chunks_exact(4) {
                    tile.extend_from_slice(&[
                        px[2],
                        px[1],
                        px[0],
                        if opaque { 0xFF } else { px[3] },
                    ]);
                }
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &tile);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Planar => {
                let mut rgb = Vec::new();
                if let Err(e) = self.planar_decoder.decode_bitmap_stream_to_rgb24(
                    &p.bitmap_data,
                    &mut rgb,
                    w as usize,
                    h as usize,
                ) {
                    warn!(
                        "EGFX[{n}]: Planar decode failed: {e} ({w}x{h}, {} bytes)",
                        p.bitmap_data.len()
                    );
                    if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                        let path = format!("{dir}/planar_fail_{n}_{w}x{h}.bin");
                        let _ = std::fs::write(&path, &p.bitmap_data);
                    }
                    return;
                }
                // rgb24 -> rgba for the surface blit
                let mut tile = Vec::with_capacity((w * h * 4) as usize);
                for px in rgb.chunks_exact(3) {
                    tile.extend_from_slice(&[px[0], px[1], px[2], 0xFF]);
                }
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &tile);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Avc420 => {
                // #425 slice 2: decode H.264/AVC420 via the host-registered
                // MediaCodec decoder (Rust owns no H.264 decoder). Capture dump
                // for triage is kept, gated on EGFX_DUMP_DIR.
                if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                    let path = format!("{dir}/avc_{n}_{:?}_{w}x{h}.bin", p.codec_id);
                    let _ = std::fs::write(&path, &p.bitmap_data);
                }
                // Parse RFX_AVC420_BITMAP_STREAM: region rects + QUANT_QUALITY,
                // then the Annex-B H.264 access unit in `stream.data`.
                let mut cursor = ReadCursor::new(&p.bitmap_data);
                let stream = match Avc420BitmapStream::decode(&mut cursor) {
                    Ok(s) => s,
                    Err(e) => {
                        warn!("EGFX[{n}]: AVC420 bitmap-stream parse failed: {e} ({} bytes)", p.bitmap_data.len());
                        return;
                    }
                };
                let Some(decoder) = self.state.read().ok().and_then(|s| s.avc_decoder.clone()) else {
                    warn!("EGFX[{n}]: AVC420 tile but no decoder registered (set_avc_decoder) — dropping");
                    return;
                };
                // Whether converting only the changed rectangles would pay off
                // depends entirely on how the server slices its frames, and we
                // had only a code comment asserting KRDP sends one full-frame
                // region (#466). Log it so a real session can confirm or refute
                // that rather than it being taken on trust: a single region the
                // size of the destination means there is nothing smaller to
                // convert, several small ones mean there is.
                debug!(
                    "EGFX[{n}]: AVC420 dest {w}x{h} at ({},{}), {} region rect(s){}",
                    r.left,
                    r.top,
                    stream.rectangles.len(),
                    if stream.rectangles.len() == 1 {
                        " (full-frame — per-region conversion would save nothing)"
                    } else {
                        ""
                    },
                );
                // The H.264 frame is a full picture the size of the destination
                // rectangle; `stream.rectangles` are changed-region hints. KRDP
                // sends one full-frame region, so blit the whole decoded frame
                // to the destination. ponytail: multi-region partial blits
                // (Windows/AVC444) collapse to a full-dest repaint here — still
                // correct pixels, just not minimal; refine in slice 3.
                // The host writes straight into this buffer rather than
                // returning one (#466). Returning a Vec<u8> cost 47ms per
                // 1080p frame in the crossing alone — measured with a decoder
                // that did no decoding — against 0.24ms for this shape. The
                // buffer is reused across frames and never published, so the
                // address handed over is valid only for the duration of the
                // call, which is the contract on `decode_into`.
                let Some(need) = crate::yuv::i420_len(w as usize, h as usize) else {
                    warn!("EGFX[{n}]: AVC420 implausible frame size {w}x{h} — dropping");
                    return;
                };
                let mut i420 = std::mem::take(&mut self.avc_i420);
                if i420.len() != need {
                    i420.resize(need, 0);
                }
                let t_avc = std::time::Instant::now();
                let written = decoder.decode_into(
                    stream.data.to_vec(),
                    w as u16,
                    h as u16,
                    i420.as_mut_ptr() as u64,
                    i420.len() as u64,
                ) as usize;
                self.perf.avc_call_us += t_avc.elapsed().as_micros() as u64;
                // A short write is a decoder bug, not a crop: the host
                // edge-replicates to w/h, so the length is a pure function of
                // the arguments. Refuse to convert a partially-filled buffer,
                // which would paint the previous frame's tail.
                if written != need {
                    if written != 0 {
                        warn!(
                            "EGFX[{n}]: AVC420 decoder wrote {written} bytes, need {need} for \
                             {w}x{h} I420 — dropping",
                        );
                    }
                    self.avc_i420 = i420;
                    return;
                }
                if self.perf.memcpy_us == 0 && !i420.is_empty() {
                    let t = std::time::Instant::now();
                    let mut probe = vec![0u8; i420.len()];
                    probe.copy_from_slice(&i420);
                    std::hint::black_box(&probe);
                    // max(1) so a sub-microsecond result does not re-arm the
                    // probe on every frame of the window.
                    self.perf.memcpy_us = (t.elapsed().as_micros() as u64).max(1);
                    self.perf.memcpy_kb = (i420.len() / 1024) as u64;
                }
                // Colour conversion is ours now (#466). The host used to do it
                // and hand back RGBA, which cost 27-109ms in a Kotlin loop and
                // sent 2.67x as many bytes across the boundary as I420 does.
                let t_yuv = std::time::Instant::now();
                let mut rgba = std::mem::take(&mut self.avc_rgba);
                let converted = crate::yuv::i420_to_rgba(&i420, w as usize, h as usize, &mut rgba);
                self.perf.yuv_us += t_yuv.elapsed().as_micros() as u64;
                self.avc_i420 = i420;
                if !converted {
                    warn!("EGFX[{n}]: AVC420 conversion refused {need} bytes for {w}x{h} — dropping");
                    self.avc_rgba = rgba;
                    return;
                }
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                let t_blit = std::time::Instant::now();
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &rgba);
                self.perf.blit_us += t_blit.elapsed().as_micros() as u64;
                self.surfaces.dirty.push((p.surface_id, r.clone()));
                // Keep the buffer for the next frame: a fresh 8.29MB Vec per
                // frame is exactly the allocation churn this change is about.
                self.avc_rgba = rgba;
            }
            Codec1Type::Avc444 | Codec1Type::Avc444v2 => {
                // #425 slice 3: AVC444 dual-stream (4:2:0 luma + chroma aux)
                // → 4:4:4. Not decoded yet; dump for capture, then drop.
                info!(
                    "EGFX[{n}]: AVC444 tile codec={:?} {}x{} {} bytes (decode NYI — #425 slice 3)",
                    p.codec_id, w, h, p.bitmap_data.len()
                );
                if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                    let path = format!("{dir}/avc_{n}_{:?}_{w}x{h}.bin", p.codec_id);
                    let _ = std::fs::write(&path, &p.bitmap_data);
                }
            }
            other => {
                // warn!, not debug!: a dropped region leaves a rectangle of the
                // screen unpainted, and at debug level that never reaches a bug
                // report — #462 was diagnosed by reading this dispatch rather
                // than any log. RemoteFx (0x3) and Alpha (0x0c) still land here.
                warn!(
                    "EGFX[{n}]: WireToSurface1 codec {other:?} not implemented —                      {w}x{h} region at ({},{}) left unpainted ({} bytes ignored)",
                    r.left,
                    r.top,
                    p.bitmap_data.len()
                );
            }
        }
    }

    fn handle_wire_to_surface2(&mut self, n: u64, p: &WireToSurface2Pdu) {
        debug!(
            "EGFX[{n}]: WireToSurface2 surface={} codec={:?} ctx={} {} bytes",
            p.surface_id,
            p.codec_id,
            p.codec_context_id,
            p.bitmap_data.len()
        );
        if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
            let path = format!(
                "{dir}/wts2_{n}_surface{}_ctx{}_codec{:?}.bin",
                p.surface_id, p.codec_context_id, p.codec_id
            );
            let _ = std::fs::write(&path, &p.bitmap_data);
        }
        match p.codec_id {
            Codec2Type::RemoteFxProgressive => {
                let mut tiles = Vec::new();
                if let Err(e) =
                    self.progressive_decoder
                        .decode(p.surface_id, &p.bitmap_data, &mut tiles)
                {
                    warn!(
                        "EGFX[{n}]: Progressive decode failed: {e} ({} bytes)",
                        p.bitmap_data.len()
                    );
                    return;
                }
                debug!(
                    "EGFX[{n}]: Progressive surface={} produced {} tile(s)",
                    p.surface_id,
                    tiles.len()
                );
                {
                    let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                        warn!("EGFX[{n}]: WireToSurface2 unknown surface {}", p.surface_id);
                        return;
                    };
                    for tile in &tiles {
                        surface.blit_rgba(u32::from(tile.x), u32::from(tile.y), 64, 64, &tile.rgba);
                    }
                }
                for tile in &tiles {
                    self.surfaces.dirty.push((
                        p.surface_id,
                        ironrdp_pdu::geometry::ExclusiveRectangle {
                            left: tile.x,
                            top: tile.y,
                            right: tile.x.saturating_add(64),
                            bottom: tile.y.saturating_add(64),
                        },
                    ));
                }
            }
        }
    }
}

/// Clip an EGFX rectangle (RDPGFX_RECT16, exclusive right/bottom) to a
/// surface of the given size. Returns `(x, y, w, h)` in surface-local
/// pixels. `(0, 0, 0, 0)` if the rect is fully outside.
fn clip_to_surface(r: &ironrdp_pdu::geometry::ExclusiveRectangle, sw: u32, sh: u32) -> (u32, u32, u32, u32) {
    let l = u32::from(r.left).min(sw);
    let t = u32::from(r.top).min(sh);
    let right = u32::from(r.right).min(sw);
    let bottom = u32::from(r.bottom).min(sh);
    if right <= l || bottom <= t {
        (0, 0, 0, 0)
    } else {
        (l, t, right - l, bottom - t)
    }
}

impl DvcClientProcessor for EgfxProcessor {}

/// If `EGFX_PDU_DUMP_DIR` is set, write the post-zgfx-decompressed bytes
/// of each [`GfxPdu`] to `<dir>/pdu_NNNN_<kind>.bin`. Useful as
/// regression / upstream-bug-report fixtures: the bytes are exactly what
/// the `<GfxPdu as Decode>::decode` parser saw, so feeding them back
/// through the same parser is a deterministic reproduction.
///
/// Names are zero-padded so a normal `ls` lists them in arrival order.
/// Logs (not panics) on I/O failure — a session shouldn't die because
/// the brewer's dump dir is full.
fn maybe_dump_pdu(n: u64, bytes: &[u8], pdu: &GfxPdu) {
    let Ok(dir) = std::env::var("EGFX_PDU_DUMP_DIR") else {
        return;
    };
    let kind = pdu_kind_label(pdu);
    let path = format!("{dir}/pdu_{n:04}_{kind}.bin");
    if let Err(e) = std::fs::write(&path, bytes) {
        warn!("EGFX_PDU_DUMP write failed for {path}: {e}");
    }
}

fn pdu_kind_label(p: &GfxPdu) -> &'static str {
    // Exhaustive match — if upstream adds a GfxPdu variant we want
    // the build to break here so we add it to the dump filename.
    match p {
        GfxPdu::CapabilitiesConfirm(_) => "capabilities_confirm",
        GfxPdu::ResetGraphics(_) => "reset_graphics",
        GfxPdu::CreateSurface(_) => "create_surface",
        GfxPdu::DeleteSurface(_) => "delete_surface",
        GfxPdu::MapSurfaceToOutput(_) => "map_surface_to_output",
        GfxPdu::MapSurfaceToScaledOutput(_) => "map_surface_to_scaled_output",
        GfxPdu::MapSurfaceToScaledWindow(_) => "map_surface_to_scaled_window",
        GfxPdu::StartFrame(_) => "start_frame",
        GfxPdu::EndFrame(_) => "end_frame",
        GfxPdu::WireToSurface1(_) => "wire_to_surface1",
        GfxPdu::WireToSurface2(_) => "wire_to_surface2",
        GfxPdu::SolidFill(_) => "solid_fill",
        GfxPdu::SurfaceToSurface(_) => "surface_to_surface",
        GfxPdu::SurfaceToCache(_) => "surface_to_cache",
        GfxPdu::CacheToSurface(_) => "cache_to_surface",
        GfxPdu::EvictCacheEntry(_) => "evict_cache_entry",
        GfxPdu::DeleteEncodingContext(_) => "delete_encoding_context",
        GfxPdu::CacheImportReply(_) => "cache_import_reply",
        // Client-origin variants (the merged GfxPdu enum covers both
        // directions); a server never legitimately sends these.
        _ => "unexpected_client_origin_pdu",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ironrdp_pdu::geometry::ExclusiveRectangle;

    /// #425: verify the RFX_AVC420_BITMAP_STREAM header parse matches the wire
    /// format observed in real KRDP captures — 1 full-frame region (0,0,1280,
    /// 800), QUANT_QUALITY, then the Annex-B H.264 access unit. The AVC420 arm
    /// relies on `stream.data` being exactly the trailing Annex-B bytes (it
    /// forwards them to the MediaCodec decoder), so this pins that contract.
    #[test]
    fn avc420_bitmap_stream_parse() {
        // Header bytes lifted verbatim from a real KRDP frame-1 capture.
        let mut buf: Vec<u8> = vec![
            0x01, 0x00, 0x00, 0x00, // numRegionRects = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x20, 0x03, // RECT16 l=0 t=0 r=1280 b=800
            0x16, 0x64, // QUANT_QUALITY: quant=22 quality=100
        ];
        // Annex-B start code + SPS NAL header (0x67) + a couple payload bytes.
        let annex_b = [0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0];
        buf.extend_from_slice(&annex_b);

        let stream = Avc420BitmapStream::decode(&mut ReadCursor::new(&buf))
            .expect("AVC420 header should parse");
        assert_eq!(stream.rectangles.len(), 1, "one region rect");
        assert_eq!(stream.quant_qual_vals.len(), 1, "one quant/quality pair");
        assert_eq!(stream.data, &annex_b, "data is the trailing Annex-B AU");
    }

    /// Encode what `start()` puts on the wire, and pull the single capability
    /// set out of it: `(version, flags)`.
    ///
    /// Reading the *encoded bytes* rather than the typed value is deliberate —
    /// the Windows behaviour this guards keys off one 32-bit word in the PDU,
    /// so the assertion should be on the word the server actually receives.
    fn advertised_capset(avc_enabled: bool) -> (u32, u32) {
        let mut proc = EgfxProcessor::new(test_state(64, 64), false, avc_enabled);
        let msgs = proc.start(0).expect("start() builds an advertise");
        assert_eq!(msgs.len(), 1, "one CapabilitiesAdvertise");
        let mut buf = vec![0u8; msgs[0].size()];
        msgs[0]
            .encode(&mut WriteCursor::new(&mut buf))
            .expect("advertise encodes");
        // RDPGFX_HEADER (8) + capsSetCount (2), then version (4) + capsDataLength (4) + capsData (4).
        assert_eq!(u16::from_le_bytes([buf[8], buf[9]]), 1, "exactly one capset advertised");
        let word = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        assert_eq!(word(14), 4, "capsDataLength is the 4-byte flags field");
        (word(10), word(18))
    }

    /// #477: with AVC on we must advertise V8.1 carrying **both**
    /// AVC420_ENABLED and SMALL_CACHE.
    ///
    /// Dropping SMALL_CACHE is not cosmetic: a Windows 11 24H2 server responds
    /// to a lone AVC420_ENABLED by closing the graphics channel, and the
    /// session spends the rest of its life on legacy fast-path bitmaps.
    /// Verified on the wire against win11-rdptest — 2/2 closed without the
    /// flag, 2/2 confirmed with it — and this pins the bit so it cannot be
    /// tidied away again.
    #[test]
    fn avc_advertise_is_v81_with_small_cache() {
        let (version, flags) = advertised_capset(true);
        assert_eq!(version, 0x0008_0105, "V8.1 — FreeRDP gates AVC420 on this version");
        assert_eq!(
            flags,
            (CapabilitiesV81Flags::AVC420_ENABLED | CapabilitiesV81Flags::SMALL_CACHE).bits(),
            "AVC420_ENABLED alone makes Windows close the graphics channel",
        );
    }

    /// The non-AVC arm still advertises V10 with AVC_DISABLED, so a server that
    /// can do H.264 doesn't send us tiles we have no decoder for.
    #[test]
    fn non_avc_advertise_is_v10_avc_disabled() {
        let (version, flags) = advertised_capset(false);
        assert_eq!(version, 0x000a_0002, "V10");
        assert_eq!(flags, CapabilitiesV10Flags::AVC_DISABLED.bits());
    }

    /// A session with a framebuffer of the given size, as connect() leaves it.
    fn test_state(w: u16, h: u16) -> Arc<RwLock<crate::SessionState>> {
        Arc::new(RwLock::new(crate::SessionState {
            connected: true,
            framebuffer: Some(crate::FrameData {
                width: w,
                height: h,
                pixels: vec![0u8; w as usize * h as usize * 4],
            }),
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }))
    }

    /// #477: the perf summary must reach the shared state, not just the
    /// Android log.
    ///
    /// This is the whole point of the change — the number was always being
    /// computed, it just could not get anywhere a reporter could copy it from.
    /// Asserting on `state` rather than on the log is what distinguishes the
    /// two, so this fails if the line goes back to being log-only.
    #[test]
    fn perf_summary_reaches_the_host_state() {
        let state = test_state(64, 32);
        let mut perf = EgfxPerf::default();

        // One short of the reporting threshold: nothing should be recorded yet,
        // or the "report every N frames" batching is not actually happening.
        perf.frames = EGFX_PERF_REPORT_FRAMES - 1;
        perf.maybe_report(&state);
        assert!(
            state.read().unwrap().perf_log.is_empty(),
            "must not report before {EGFX_PERF_REPORT_FRAMES} frames",
        );

        perf.frames = EGFX_PERF_REPORT_FRAMES;
        perf.decode_us = 3_000;
        perf.flush_us = 1_000;
        perf.zgfx_us = 500;
        perf.maybe_report(&state);

        let log = state.read().unwrap().perf_log.clone();
        assert_eq!(log.len(), 1, "one summary line recorded");
        assert!(log[0].starts_with("EGFX perf:"), "recognisable line: {}", log[0]);
        assert!(
            log[0].contains(&format!("over {EGFX_PERF_REPORT_FRAMES} frames")),
            "carries the frame count: {}",
            log[0],
        );

        // Reporting resets the accumulator, so a second call at the same
        // instant must not emit a duplicate off stale counters.
        perf.maybe_report(&state);
        assert_eq!(
            state.read().unwrap().perf_log.len(),
            1,
            "counters reset after reporting",
        );
    }

    /// Surface 0 covering the whole output, which is what a server sets up
    /// before it starts sending tiles.
    fn create_full_surface(proc: &mut EgfxProcessor, out: &mut Vec<DvcMessage>, w: u16, h: u16) {
        proc.dispatch(
            1,
            &GfxPdu::CreateSurface(ironrdp_egfx::pdu::CreateSurfacePdu {
                surface_id: 0,
                width: w,
                height: h,
                pixel_format: PixelFormat::XRgb,
            }),
            out,
        );
        proc.dispatch(
            2,
            &GfxPdu::MapSurfaceToOutput(ironrdp_egfx::pdu::MapSurfaceToOutputPdu {
                surface_id: 0,
                output_origin_x: 0,
                output_origin_y: 0,
            }),
            out,
        );
    }

    /// #462: rectangular regions never painted on Windows 11. The dispatch
    /// silently dropped RDPGFX_CODECID_UNCOMPRESSED — the baseline encoding a
    /// server may fall back to for ANY region — so those regions stayed blank
    /// or stale. These pin that the raw pixels land, in the right place, in the
    /// right channel order.
    #[test]
    fn uncompressed_tile_is_painted_in_rgba_order() {
        let state = test_state(64, 64);
        let mut proc = EgfxProcessor::new(state.clone(), false, false);
        proc.capabilities_received = true;
        let mut out = Vec::new();
        create_full_surface(&mut proc, &mut out, 64, 64);

        // One 2x1 tile at (3,5). Wire order for XRgb is B,G,R,X.
        let pixels: Vec<u8> = vec![0x10, 0x20, 0x30, 0x00, 0x40, 0x50, 0x60, 0x00];
        proc.dispatch(
            10,
            &GfxPdu::WireToSurface1(WireToSurface1Pdu {
                surface_id: 0,
                codec_id: Codec1Type::Uncompressed,
                pixel_format: PixelFormat::XRgb,
                destination_rectangle: ExclusiveRectangle { left: 3, top: 5, right: 5, bottom: 6 },
                bitmap_data: pixels,
            }),
            &mut out,
        );

        let surface = proc.surfaces.surface(0).expect("surface");
        let at = |x: usize, y: usize| {
            let i = (y * 64 + x) * 4;
            surface.pixels[i..i + 4].to_vec()
        };
        // B,G,R,X on the wire becomes R,G,B,A in the surface; XRgb forces opaque.
        assert_eq!(at(3, 5), vec![0x30, 0x20, 0x10, 0xFF], "first pixel");
        assert_eq!(at(4, 5), vec![0x60, 0x50, 0x40, 0xFF], "second pixel");
        // Neighbours untouched — the tile must not smear.
        assert_eq!(at(5, 5), vec![0, 0, 0, 0], "pixel past the tile");
        assert_eq!(at(3, 6), vec![0, 0, 0, 0], "pixel below the tile");
    }

    /// #466/#477: the AVC round-trip and blit timers must actually fire on the
    /// AVC420 path.
    ///
    /// Both reporters' logs show the Rust-side per-frame decode cost far
    /// exceeding what the Kotlin decoder reports for the same frames — 107ms
    /// against 309ms at 2560x1440 — and these two counters exist to say where
    /// the difference lives. An earlier timer on this same path was attached to
    /// an event EGFX never emits and produced nothing but zeroes for a whole
    /// session, so "the numbers appear in the line" is not enough: this drives
    /// a real dispatch through a decoder that sleeps a known time, and fails if
    /// either counter stays at zero.
    /// A session the server never sends H.264 to still reports a perf line,
    /// and that line must not carry a yardstick that never ran. "alloc+copy of
    /// 0KB took 0us" reads like a result saying copying is free.
    ///
    /// Found by running the instrument against a real Windows 11 server, which
    /// used progressive for ordinary desktop updates even with AVC420
    /// negotiated — so the AVC counters, and the probe, stayed at zero.
    #[test]
    fn a_perf_line_omits_the_yardstick_when_no_avc_frame_ran() {
        let state = test_state(64, 64);
        let mut perf = EgfxPerf { frames: EGFX_PERF_REPORT_FRAMES, decode_us: 1_000, ..Default::default() };
        perf.maybe_report(&state);
        let lines = state.read().unwrap().perf_log.clone();
        assert_eq!(1, lines.len(), "the perf line must still be reported");
        assert!(
            !lines[0].contains("yardstick"),
            "a probe that never ran must not appear as a measurement: {}",
            lines[0],
        );

        let state2 = test_state(64, 64);
        let mut ran = EgfxPerf {
            frames: EGFX_PERF_REPORT_FRAMES,
            decode_us: 1_000,
            memcpy_us: 42,
            memcpy_kb: 5_400,
            ..Default::default()
        };
        ran.maybe_report(&state2);
        let with = state2.read().unwrap().perf_log[0].clone();
        assert!(with.contains("yardstick: alloc+copy of 5400KB took 42us"), "got {with}");
    }

    #[test]
    fn avc420_round_trip_and_blit_are_measured() {
        struct SlowDecoder {
            delay: std::time::Duration,
        }
        impl crate::Avc420Decoder for SlowDecoder {
            fn decode_into(
                &self,
                _annex_b: Vec<u8>,
                w: u16,
                h: u16,
                dst_addr: u64,
                dst_len: u64,
            ) -> u32 {
                std::thread::sleep(self.delay);
                // A mid-grey I420 frame: luma 128, chroma neutral. Written
                // through the address exactly as the host does, so the test
                // exercises the real contract rather than a friendlier one.
                let len = crate::yuv::i420_len(usize::from(w), usize::from(h)).unwrap();
                assert!(len as u64 <= dst_len, "caller must size the buffer");
                // SAFETY: the caller guarantees `dst_len` writable bytes at
                // `dst_addr` for the duration of this call.
                unsafe {
                    std::ptr::write_bytes(dst_addr as *mut u8, 128u8, len);
                }
                len as u32
            }
        }

        let (w, h) = (64u16, 64u16);
        let state = test_state(w, h);
        let delay = std::time::Duration::from_millis(20);
        state.write().unwrap().avc_decoder = Some(Arc::new(SlowDecoder { delay }));

        let mut proc = EgfxProcessor::new(state.clone(), false, false);
        proc.capabilities_received = true;
        let mut out = Vec::new();
        create_full_surface(&mut proc, &mut out, w, h);

        // RFX_AVC420_BITMAP_STREAM: one full-frame region, then an Annex-B AU.
        let mut payload: Vec<u8> = vec![0x01, 0x00, 0x00, 0x00];
        payload.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x40, 0x00]);
        payload.extend_from_slice(&[0x16, 0x64]);
        payload.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0]);

        proc.dispatch(
            12,
            &GfxPdu::WireToSurface1(WireToSurface1Pdu {
                surface_id: 0,
                codec_id: Codec1Type::Avc420,
                pixel_format: PixelFormat::XRgb,
                destination_rectangle: ExclusiveRectangle { left: 0, top: 0, right: w, bottom: h },
                bitmap_data: payload,
            }),
            &mut out,
        );

        assert!(
            proc.perf.avc_call_us >= delay.as_micros() as u64,
            "the AVC round trip must be timed: got {}us for a decoder that slept {}us",
            proc.perf.avc_call_us,
            delay.as_micros(),
        );
        assert!(proc.perf.blit_us > 0, "the blit must be timed, got 0us");
        // The yardstick has to have actually run. A probe that stays at zero
        // reports "0us to copy 0KB", which reads like a result rather than
        // like a probe that never fired — the exact failure this test's
        // predecessor was written for.
        assert!(proc.perf.memcpy_us > 0, "the alloc+copy yardstick must run, got 0us");
        assert_eq!(
            proc.perf.memcpy_kb,
            (crate::yuv::i420_len(usize::from(w), usize::from(h)).unwrap() / 1024) as u64,
            "the yardstick must size itself from the frame it is a yardstick for",
        );
        assert!(proc.perf.yuv_us > 0, "the I420 conversion must be timed, got 0us");
        // And the frame really was painted — otherwise the timers could be
        // measuring a path that bails out before doing the work.
        //
        // The value checked for is the *converted* one: neutral I420
        // (Y=128, U=V=128) is R=G=B=130 through BT.601 limited range. Asserting
        // 130 rather than 128 is what distinguishes "the conversion ran" from
        // "the decoder's bytes were blitted raw", which is the mistake this
        // whole change could most easily make (#466).
        let surface = proc.surfaces.surface(0).expect("surface");
        assert!(
            surface.pixels.iter().any(|&b| b == 130),
            "the converted frame must reach the surface",
        );
        assert!(
            !surface.pixels.iter().any(|&b| b == 128),
            "raw I420 luma must not appear in the surface — that would mean no conversion",
        );
    }

    /// A short payload must be refused rather than read past its end.
    #[test]
    fn uncompressed_tile_shorter_than_its_rectangle_is_dropped() {
        let state = test_state(64, 64);
        let mut proc = EgfxProcessor::new(state.clone(), false, false);
        proc.capabilities_received = true;
        let mut out = Vec::new();
        create_full_surface(&mut proc, &mut out, 64, 64);

        proc.dispatch(
            11,
            &GfxPdu::WireToSurface1(WireToSurface1Pdu {
                surface_id: 0,
                codec_id: Codec1Type::Uncompressed,
                pixel_format: PixelFormat::XRgb,
                // Claims 4x4 (64 bytes) but carries 8.
                destination_rectangle: ExclusiveRectangle { left: 0, top: 0, right: 4, bottom: 4 },
                bitmap_data: vec![0xFF; 8],
            }),
            &mut out,
        );

        let surface = proc.surfaces.surface(0).expect("surface");
        assert!(
            surface.pixels.iter().all(|&b| b == 0),
            "a truncated tile must paint nothing rather than partially blit",
        );
    }

    /// #474/#467: KRDP echoes the client-requested size (1280x800) in Demand
    /// Active but streams the physical 2560x1440 monitor over EGFX. The
    /// framebuffer must follow ResetGraphics or everything past the old
    /// bounds is clipped (top-left-only rendering).
    #[test]
    fn reset_graphics_resizes_framebuffer() {
        use std::sync::Mutex;
        use ironrdp_egfx::pdu::{
            Color, CreateSurfacePdu, EndFramePdu, MapSurfaceToOutputPdu, PixelFormat as GfxPixelFormat,
            ResetGraphicsPdu, SolidFillPdu,
        };
        use ironrdp_pdu::geometry::ExclusiveRectangle;

        struct RecordingCb(Mutex<Vec<(u16, u16)>>);
        impl crate::FrameCallback for RecordingCb {
            fn on_frame_update(&self, _x: u16, _y: u16, _w: u16, _h: u16) {}
            fn on_resize(&self, width: u16, height: u16) {
                self.0.lock().unwrap().push((width, height));
            }
        }

        let cb = Arc::new(RecordingCb(Mutex::new(Vec::new())));
        let state = Arc::new(RwLock::new(crate::SessionState {
            connected: true,
            // What connect() allocates: the client-requested size the server
            // echoed in Demand Active.
            framebuffer: Some(crate::FrameData {
                width: 1280,
                height: 800,
                pixels: vec![0u8; 1280 * 800 * 4],
            }),
            dirty_rects: Vec::new(),
            frame_callback: Some(cb.clone()),
            clipboard_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }));
        let mut proc = EgfxProcessor::new(state.clone(), false, true);
        proc.capabilities_received = true;

        let mut out = Vec::new();
        proc.dispatch(
            1,
            &GfxPdu::ResetGraphics(ResetGraphicsPdu {
                width: 2560,
                height: 1440,
                monitors: Vec::new(),
            }),
            &mut out,
        );
        assert_eq!(
            *cb.0.lock().unwrap(),
            vec![(2560, 1440)],
            "on_resize must fire with the server's real output size"
        );

        // A fill in the bottom-right quadrant — entirely outside the old
        // 1280x800 bounds — must land in the framebuffer.
        proc.dispatch(
            2,
            &GfxPdu::CreateSurface(CreateSurfacePdu {
                surface_id: 0,
                width: 2560,
                height: 1440,
                pixel_format: GfxPixelFormat::XRgb,
            }),
            &mut out,
        );
        proc.dispatch(
            3,
            &GfxPdu::MapSurfaceToOutput(MapSurfaceToOutputPdu {
                surface_id: 0,
                output_origin_x: 0,
                output_origin_y: 0,
            }),
            &mut out,
        );
        proc.dispatch(
            4,
            &GfxPdu::SolidFill(SolidFillPdu {
                surface_id: 0,
                fill_pixel: Color { b: 1, g: 2, r: 3, xa: 255 },
                rectangles: vec![ExclusiveRectangle {
                    left: 0,
                    top: 0,
                    right: 2560,
                    bottom: 1440,
                }],
            }),
            &mut out,
        );
        proc.dispatch(5, &GfxPdu::EndFrame(EndFramePdu { frame_id: 1 }), &mut out);

        let s = state.read().unwrap();
        let fb = s.framebuffer.as_ref().expect("framebuffer present");
        assert_eq!((fb.width, fb.height), (2560, 1440), "framebuffer resized");
        // Pixel at (2000, 1200): RGBA bytes of the fill colour, not black.
        let off = (1200 * 2560 + 2000) * 4;
        assert_eq!(
            &fb.pixels[off..off + 4],
            &[3, 2, 1, 255],
            "content beyond the old 1280x800 clip must render"
        );
    }
}
