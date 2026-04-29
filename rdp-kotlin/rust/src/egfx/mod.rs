//! EGFX (MS-RDPEGFX) client over DRDYNVC.
//!
//! Phase 2 scope: open the channel, exchange capabilities, log every server
//! PDU we observe. No surface management, no codec decoding yet — that lands
//! in egfx::surface and egfx::rfx in Phase 3.
//!
//! Channel name: "Microsoft::Windows::RDS::Graphics".

use std::sync::{Arc, RwLock};

use ironrdp_core::{impl_as_any, Encode, EncodeResult, ReadCursor, WriteCursor};
use ironrdp_dvc::{DvcClientProcessor, DvcEncode, DvcMessage, DvcProcessor};
use ironrdp_graphics::zgfx::Decompressor;
use ironrdp_pdu::rdp::vc::dvc::gfx::{
    CapabilitiesAdvertisePdu, CapabilitiesV10Flags, CapabilitySet, ClientPdu, ServerPdu,
};
use ironrdp_pdu::{decode_err, PduResult};
use log::{debug, info, warn};

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

/// Phase-2 EGFX processor: caps + logging only.
pub struct EgfxProcessor {
    _state: Arc<RwLock<SessionState>>,
    capabilities_received: bool,
    server_pdu_count: u64,
    /// MS-RDPEGFX wraps every DVC payload in an RDP_SEGMENTED_DATA PDU
    /// with ZGFX (RDP 8.0) bulk compression. The decompressor keeps a
    /// 2.5 MB sliding history shared across the whole channel lifetime.
    zgfx: Decompressor,
}

impl EgfxProcessor {
    pub fn new(state: Arc<RwLock<SessionState>>) -> Self {
        Self {
            _state: state,
            capabilities_received: false,
            server_pdu_count: 0,
            zgfx: Decompressor::new(),
        }
    }
}

impl_as_any!(EgfxProcessor);

impl DvcProcessor for EgfxProcessor {
    fn channel_name(&self) -> &str {
        CHANNEL_NAME
    }

    /// Sent immediately after the DVC is created. Advertise RemoteFX-only
    /// capabilities (V10 with AVC_DISABLED) so the server picks RFX, not AVC —
    /// AVC decoding is Phase 2 work in the original plan, out of scope here.
    fn start(&mut self, _channel_id: u32) -> PduResult<Vec<DvcMessage>> {
        let caps = CapabilitiesAdvertisePdu(vec![CapabilitySet::V10 {
            flags: CapabilitiesV10Flags::AVC_DISABLED,
        }]);
        info!("EGFX: sending CapabilitiesAdvertise(V10, AVC_DISABLED)");
        let msg: DvcMessage = Box::new(GfxClientMessage(ClientPdu::CapabilitiesAdvertise(caps)));
        Ok(vec![msg])
    }

    fn process(&mut self, _channel_id: u32, payload: &[u8]) -> PduResult<Vec<DvcMessage>> {
        self.server_pdu_count = self.server_pdu_count.saturating_add(1);
        let n = self.server_pdu_count;
        // Step 1: ZGFX decompress (every EGFX wire payload is wrapped).
        let mut decompressed = Vec::with_capacity(payload.len() * 4);
        if let Err(e) = self.zgfx.decompress(payload, &mut decompressed) {
            warn!("EGFX[{n}]: zgfx decompress failed ({e:?}); skipping {} byte payload", payload.len());
            return Ok(Vec::new());
        }
        debug!(
            "EGFX[{n}] zgfx in={} out={} (ratio {:.2}x)",
            payload.len(),
            decompressed.len(),
            decompressed.len() as f32 / payload.len().max(1) as f32
        );
        // Step 2: decode one or more concatenated ServerPdus.
        let mut cur = ReadCursor::new(&decompressed);
        let pdu = match <ServerPdu as ironrdp_core::Decode>::decode(&mut cur) {
            Ok(p) => p,
            Err(e) => {
                let head_hex: String = decompressed
                    .iter()
                    .take(32)
                    .map(|b| format!("{:02x}", b))
                    .collect::<Vec<_>>()
                    .join(" ");
                warn!(
                    "EGFX[{n}]: decode failed ({e}); decompressed head: {}",
                    head_hex
                );
                return Ok(Vec::new());
            }
        };
        match &pdu {
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
            }
            ServerPdu::CreateSurface(p) => debug!(
                "EGFX[{n}]: CreateSurface id={} {}x{} pixfmt={:?}",
                p.surface_id, p.width, p.height, p.pixel_format
            ),
            ServerPdu::DeleteSurface(p) => debug!("EGFX[{n}]: DeleteSurface id={}", p.surface_id),
            ServerPdu::MapSurfaceToOutput(p) => debug!(
                "EGFX[{n}]: MapSurfaceToOutput id={} ->({},{})",
                p.surface_id, p.output_origin_x, p.output_origin_y
            ),
            ServerPdu::StartFrame(p) => debug!(
                "EGFX[{n}]: StartFrame frame_id={} timestamp={:?}",
                p.frame_id, p.timestamp
            ),
            ServerPdu::EndFrame(p) => debug!("EGFX[{n}]: EndFrame frame_id={}", p.frame_id),
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
            ServerPdu::SolidFill(p) => debug!(
                "EGFX[{n}]: SolidFill surface={} rects={} colour={:?}",
                p.surface_id,
                p.rectangles.len(),
                p.fill_pixel
            ),
            ServerPdu::SurfaceToSurface(_)
            | ServerPdu::SurfaceToCache(_)
            | ServerPdu::CacheToSurface(_)
            | ServerPdu::EvictCacheEntry(_)
            | ServerPdu::DeleteEncodingContext(_)
            | ServerPdu::CacheImportReply(_)
            | ServerPdu::MapSurfaceToScaledOutput(_)
            | ServerPdu::MapSurfaceToScaledWindow(_) => {
                debug!("EGFX[{n}]: {:?}", std::mem::discriminant(&pdu));
            }
        }
        if !self.capabilities_received {
            warn!("EGFX[{n}]: server PDU before CapabilitiesConfirm");
        }
        Ok(Vec::new())
    }
}

impl DvcClientProcessor for EgfxProcessor {}
