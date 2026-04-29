//! EGFX (MS-RDPEGFX) client over DRDYNVC.
//!
//! Phase 3a scope: surface + cache management, SolidFill, frame ACKs.
//! Codec-decoded WireToSurface tiles land in 3b — without codec decode the
//! cache stays empty so cache replays are no-ops, but everything else works.
//!
//! Channel name: "Microsoft::Windows::RDS::Graphics".

use std::sync::{Arc, RwLock};

use ironrdp_core::{impl_as_any, Encode, EncodeResult, ReadCursor, WriteCursor};
use ironrdp_dvc::{DvcClientProcessor, DvcEncode, DvcMessage, DvcProcessor};
use ironrdp_graphics::zgfx::Decompressor;
use ironrdp_pdu::rdp::vc::dvc::gfx::{
    CapabilitiesAdvertisePdu, CapabilitiesV10Flags, CapabilitySet, ClientPdu, Codec1Type,
    Codec2Type, FrameAcknowledgePdu, QueueDepth, ServerPdu, WireToSurface1Pdu, WireToSurface2Pdu,
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

/// Wrapper so we can implement [`DvcEncode`] for upstream's [`ClientPdu`].
struct GfxClientMessage(ClientPdu);

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
pub struct EgfxProcessor {
    state: Arc<RwLock<SessionState>>,
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
}

impl EgfxProcessor {
    pub fn new(state: Arc<RwLock<SessionState>>) -> Self {
        Self {
            state,
            capabilities_received: false,
            server_pdu_count: 0,
            zgfx: Decompressor::new(),
            total_frames_decoded: 0,
            surfaces: SurfaceManager::new(),
            clear_decoder: ClearDecoder::new(),
            progressive_decoder: ProgressiveDecoder::new(),
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
        let caps = CapabilitiesAdvertisePdu(vec![CapabilitySet::V10 {
            flags: CapabilitiesV10Flags::AVC_DISABLED,
        }]);
        info!("EGFX: sending CapabilitiesAdvertise(V10, AVC_DISABLED)");
        let msg: DvcMessage = Box::new(GfxClientMessage(ClientPdu::CapabilitiesAdvertise(caps)));
        Ok(vec![msg])
    }

    fn process(&mut self, _channel_id: u32, payload: &[u8]) -> PduResult<Vec<DvcMessage>> {
        // Step 1: ZGFX decompress (every EGFX wire payload is wrapped).
        let mut decompressed = Vec::with_capacity(payload.len() * 4);
        if let Err(e) = self.zgfx.decompress(payload, &mut decompressed) {
            warn!(
                "EGFX zgfx decompress failed ({e:?}); skipping {} byte payload",
                payload.len()
            );
            return Ok(Vec::new());
        }
        debug!(
            "EGFX zgfx in={} out={} (ratio {:.2}x)",
            payload.len(),
            decompressed.len(),
            decompressed.len() as f32 / payload.len().max(1) as f32
        );
        // Step 2: decode every concatenated ServerPdu in the buffer. A single
        // DVC message often carries StartFrame / WireToSurface* / EndFrame
        // back-to-back for one surface update.
        let mut out_messages: Vec<DvcMessage> = Vec::new();
        let mut cur = ReadCursor::new(&decompressed);
        while !cur.is_empty() {
            self.server_pdu_count = self.server_pdu_count.saturating_add(1);
            let n = self.server_pdu_count;
            let pdu = match <ServerPdu as ironrdp_core::Decode>::decode(&mut cur) {
                Ok(p) => p,
                Err(e) => {
                    warn!(
                        "EGFX[{n}]: decode failed ({e}); {} bytes remaining",
                        cur.len()
                    );
                    break;
                }
            };
            self.dispatch(n, &pdu, &mut out_messages);
        }
        Ok(out_messages)
    }
}

impl EgfxProcessor {
    /// Inspect a single decoded server PDU. Push any client-side reply
    /// (frame ack, etc.) into `out`.
    fn dispatch(&mut self, n: u64, pdu: &ServerPdu, out: &mut Vec<DvcMessage>) {
        match pdu {
            ServerPdu::CapabilitiesConfirm(c) => {
                self.capabilities_received = true;
                info!("EGFX[{n}]: CapabilitiesConfirm {:?}", c.0);
            }
            ServerPdu::ResetGraphics(p) => {
                info!(
                    "EGFX[{n}]: ResetGraphics width={} height={} monitors={}",
                    p.width,
                    p.height,
                    p.monitors.len()
                );
                self.surfaces.reset();
            }
            ServerPdu::CreateSurface(p) => {
                debug!(
                    "EGFX[{n}]: CreateSurface id={} {}x{} pixfmt={:?}",
                    p.surface_id, p.width, p.height, p.pixel_format
                );
                self.surfaces.create_surface(p);
            }
            ServerPdu::DeleteSurface(p) => {
                debug!("EGFX[{n}]: DeleteSurface id={}", p.surface_id);
                self.surfaces.delete_surface(p);
            }
            ServerPdu::MapSurfaceToOutput(p) => {
                debug!(
                    "EGFX[{n}]: MapSurfaceToOutput id={} ->({},{})",
                    p.surface_id, p.output_origin_x, p.output_origin_y
                );
                self.surfaces
                    .map_to_output(p.surface_id, p.output_origin_x as i32, p.output_origin_y as i32);
            }
            ServerPdu::StartFrame(p) => debug!(
                "EGFX[{n}]: StartFrame frame_id={} timestamp={:?}",
                p.frame_id, p.timestamp
            ),
            ServerPdu::EndFrame(p) => {
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
                out.push(Box::new(GfxClientMessage(ClientPdu::FrameAcknowledge(ack))));
            }
            ServerPdu::WireToSurface1(p) => self.handle_wire_to_surface1(n, p),
            ServerPdu::WireToSurface2(p) => self.handle_wire_to_surface2(n, p),
            ServerPdu::SolidFill(p) => {
                debug!(
                    "EGFX[{n}]: SolidFill surface={} rects={} colour={:?}",
                    p.surface_id,
                    p.rectangles.len(),
                    p.fill_pixel
                );
                self.surfaces.solid_fill(p);
            }
            ServerPdu::SurfaceToSurface(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToSurface src={} dst={} points={}",
                    p.source_surface_id,
                    p.destination_surface_id,
                    p.destination_points.len()
                );
                self.surfaces.surface_to_surface(p);
            }
            ServerPdu::SurfaceToCache(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToCache surface={} key=0x{:016x} cache_slot={}",
                    p.surface_id, p.cache_key, p.cache_slot
                );
                self.surfaces.surface_to_cache(p);
            }
            ServerPdu::CacheToSurface(p) => {
                debug!(
                    "EGFX[{n}]: CacheToSurface cache_slot={} surface={} positions={}",
                    p.cache_slot,
                    p.surface_id,
                    p.destination_points.len()
                );
                self.surfaces.cache_to_surface(p);
            }
            ServerPdu::EvictCacheEntry(p) => {
                debug!("EGFX[{n}]: EvictCacheEntry cache_slot={}", p.cache_slot);
                self.surfaces.evict_cache(p);
            }
            ServerPdu::DeleteEncodingContext(_) => debug!("EGFX[{n}]: DeleteEncodingContext"),
            ServerPdu::CacheImportReply(_) => debug!("EGFX[{n}]: CacheImportReply"),
            ServerPdu::MapSurfaceToScaledOutput(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledOutput")
            }
            ServerPdu::MapSurfaceToScaledWindow(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledWindow")
            }
        }
        if !self.capabilities_received {
            warn!("EGFX[{n}]: server PDU before CapabilitiesConfirm");
        }
    }
}

impl EgfxProcessor {
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
                    // Surface is RGBA8888 (R,G,B,A bytes); framebuffer is
                    // BGRA in memory (Android ARGB_8888 little-endian).
                    // Swap R<->B per pixel during the copy.
                    let src_row = &surface.pixels[s_off..s_off + copy_w * 4];
                    let dst_row = &mut fb.pixels[d_off..d_off + copy_w * 4];
                    for px in 0..copy_w {
                        let i = px * 4;
                        dst_row[i] = src_row[i + 2];     // B
                        dst_row[i + 1] = src_row[i + 1]; // G
                        dst_row[i + 2] = src_row[i];     // R
                        dst_row[i + 3] = src_row[i + 3]; // A
                    }
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
            cb.on_frame_update(
                bb_l as u16,
                bb_t as u16,
                (bb_r - bb_l) as u16,
                (bb_b - bb_t) as u16,
            );
        }
    }

    /// If `EGFX_DUMP_DIR` is set, write surface 0 as a PPM after each
    /// EndFrame. Useful for visual diff against a VNC reference shot from
    /// the host smoke driver — no extra image-crate dependency.
    fn maybe_dump_surface(&self, frame_id: u32) {
        let Ok(dir) = std::env::var("EGFX_DUMP_DIR") else {
            return;
        };
        let Some(s) = self.surfaces.surface(0) else {
            return;
        };
        let path = format!("{dir}/surface0_frame{frame_id:04}.ppm");
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

    fn handle_wire_to_surface1(&mut self, n: u64, p: &WireToSurface1Pdu) {
        // MS-RDPEGFX `RDPGFX_RECT16` uses *exclusive* right/bottom for
        // WireToSurface destinations (matches FreeRDP's `width = right -
        // left`). ironrdp names the type `InclusiveRectangle`, but for this
        // PDU the right/bottom indices are one-past-end, so we drop the +1.
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
            other => {
                debug!(
                    "EGFX[{n}]: WireToSurface1 codec {other:?} not yet handled ({} bytes ignored)",
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
                        ironrdp_pdu::geometry::InclusiveRectangle {
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
fn clip_to_surface(r: &ironrdp_pdu::geometry::InclusiveRectangle, sw: u32, sh: u32) -> (u32, u32, u32, u32) {
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
