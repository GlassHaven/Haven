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
    CapabilitiesAdvertisePdu, CapabilitiesV10Flags, CapabilitySet, ClientPdu, FrameAcknowledgePdu,
    QueueDepth, ServerPdu,
};
use ironrdp_pdu::PduResult;
use log::{debug, info, warn};

mod surface;

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
    _state: Arc<RwLock<SessionState>>,
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
}

impl EgfxProcessor {
    pub fn new(state: Arc<RwLock<SessionState>>) -> Self {
        Self {
            _state: state,
            capabilities_received: false,
            server_pdu_count: 0,
            zgfx: Decompressor::new(),
            total_frames_decoded: 0,
            surfaces: SurfaceManager::new(),
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
                let ack = FrameAcknowledgePdu {
                    queue_depth: QueueDepth::Unavailable, // FreeRDP-equivalent of "send the next frame"
                    frame_id: p.frame_id,
                    total_frames_decoded: self.total_frames_decoded,
                };
                out.push(Box::new(GfxClientMessage(ClientPdu::FrameAcknowledge(ack))));
            }
            ServerPdu::WireToSurface1(p) => debug!(
                "EGFX[{n}]: WireToSurface1 surface={} codec={:?} {} bytes",
                p.surface_id,
                p.codec_id,
                p.bitmap_data.len()
            ),
            ServerPdu::WireToSurface2(p) => debug!(
                "EGFX[{n}]: WireToSurface2 surface={} codec={:?} ctx={} {} bytes",
                p.surface_id,
                p.codec_id,
                p.codec_context_id,
                p.bitmap_data.len()
            ),
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

impl DvcClientProcessor for EgfxProcessor {}
