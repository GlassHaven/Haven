//! RemoteFX Progressive (MS-RDPRFX 2.2.4 — 2.2.7) decoder.
//!
//! Progressive is what Windows uses for photographic / smoothly-varying
//! desktop tiles that ClearCodec doesn't handle (cmd window backgrounds,
//! image previews, anti-aliased graphics under high-DPI). Without this
//! decoder our EGFX surface paints those regions as black.
//!
//! Wire structure: a stream of WBT_* blocks, each with a `u16 type` +
//! `u32 length` header. The interesting top-level blocks are:
//!
//! ```text
//!   WBT_SYNC          (0xCCC0)  magic 0xCACCACCA + version 0x0100
//!   WBT_CONTEXT       (0xCCC3)  ctxId u8 + tileSize u16 + flags u8
//!                                flags & 0x01 = RFX_SUBBAND_DIFFING
//!   WBT_FRAME_BEGIN   (0xCCC1)  frameIndex u32 + regionCount u16
//!   WBT_REGION        (0xCCC4)  see [`parse_region`]
//!   WBT_FRAME_END     (0xCCC2)  empty
//! ```
//!
//! WBT_REGION carries the actual tile data inline as nested
//! WBT_TILE_SIMPLE / WBT_TILE_FIRST / WBT_TILE_UPGRADE blocks. SIMPLE and
//! FIRST share the same decode pipeline (FIRST has an extra `quality`
//! byte; SIMPLE pretends quality=0xFF). UPGRADE is a refinement pass on
//! top of a previously-decoded FIRST tile and requires per-tile state
//! (sign + current buffers) to survive across PDUs — we currently log
//! and skip it. Static UI tiles arrive as FIRST/SIMPLE so this covers
//! the ~99% case.
//!
//! Per-tile pipeline (FIRST/SIMPLE):
//!
//! 1. RLGR1-decode the Y / Cb / Cr encoded buffers (via
//!    `ironrdp_graphics::rlgr::decode`) into 4096-element i16 subband
//!    buffers (one per plane).
//! 2. Apply per-subband left-shift by `quant + progQuant - 1` for each
//!    of HL1..HH3 + LL3. The shift values come from the per-tile quant
//!    indices into the region's quant table.
//! 3. Inverse DWT — extrapolate-mode (when region.flags &
//!    RFX_DWT_REDUCE_EXTRAPOLATE) with asymmetric L/H subband counts,
//!    or classic-mode (matches `ironrdp_graphics::dwt::decode`).
//! 4. Convert the 64×64 i16 YCbCr planes into RGBA8888.
//!
//! Reference: FreeRDP 3.16 `libfreerdp/codec/progressive.c` (Apache 2.0).
//! Algorithm ported, code is original.

use std::io;

use ironrdp_graphics::rlgr;
use ironrdp_pdu::codecs::rfx::EntropyAlgorithm;
use log::{debug, warn};

// Block type constants (MS-RDPRFX 2.2.4.1).
const WBT_SYNC: u16 = 0xCCC0;
const WBT_FRAME_BEGIN: u16 = 0xCCC1;
const WBT_FRAME_END: u16 = 0xCCC2;
const WBT_CONTEXT: u16 = 0xCCC3;
const WBT_REGION: u16 = 0xCCC4;
const WBT_TILE_SIMPLE: u16 = 0xCCC5;
const WBT_TILE_FIRST: u16 = 0xCCC6;
const WBT_TILE_UPGRADE: u16 = 0xCCC7;

const SYNC_MAGIC: u32 = 0xCACC_ACCA;
const SYNC_VERSION: u16 = 0x0100;

// Region/context/tile flag bits (all happen to be 0x01 but apply to
// different blocks).
const RFX_DWT_REDUCE_EXTRAPOLATE: u8 = 0x01;
const RFX_TILE_DIFFERENCE: u8 = 0x01;

// Subband buffer is always 4096 i16 values regardless of mode (the per-
// band element counts differ between extrapolate/non-extrapolate but
// always sum to 4096). 64×64 = 4096 final pixels per plane.
const SUBBAND_LEN: usize = 4096;

/// Per-channel quantisation table entry. Five wire bytes pack ten
/// 4-bit fields (LL3..HH1) per `progressive_component_codec_quant_read`.
#[derive(Debug, Default, Clone, Copy)]
struct Quant {
    ll3: u8,
    hl3: u8,
    lh3: u8,
    hh3: u8,
    hl2: u8,
    lh2: u8,
    hh2: u8,
    hl1: u8,
    lh1: u8,
    hh1: u8,
}

impl Quant {
    fn read(b: [u8; 5]) -> Self {
        Self {
            ll3: b[0] & 0x0F,
            hl3: (b[0] >> 4) & 0x0F,
            lh3: b[1] & 0x0F,
            hh3: (b[1] >> 4) & 0x0F,
            hl2: b[2] & 0x0F,
            lh2: (b[2] >> 4) & 0x0F,
            hh2: b[3] & 0x0F,
            hl1: (b[3] >> 4) & 0x0F,
            lh1: b[4] & 0x0F,
            hh1: (b[4] >> 4) & 0x0F,
        }
    }

    /// Sum two quant tables component-wise.
    fn add(&self, other: &Quant) -> Quant {
        Quant {
            ll3: self.ll3 + other.ll3,
            hl3: self.hl3 + other.hl3,
            lh3: self.lh3 + other.lh3,
            hh3: self.hh3 + other.hh3,
            hl2: self.hl2 + other.hl2,
            lh2: self.lh2 + other.lh2,
            hh2: self.hh2 + other.hh2,
            hl1: self.hl1 + other.hl1,
            lh1: self.lh1 + other.lh1,
            hh1: self.hh1 + other.hh1,
        }
    }
}

#[derive(Debug, Default)]
struct ProgQuant {
    quality: u8,
    y: Quant,
    cb: Quant,
    cr: Quant,
}

/// One decoded 64×64 RGBA tile, ready to blit into a surface.
pub struct DecodedTile {
    #[allow(dead_code)]
    pub surface_id: u16,
    /// Surface-local pixel coords of the tile's top-left.
    pub x: u16,
    pub y: u16,
    /// 64*64*4 = 16384 bytes RGBA8888.
    pub rgba: Vec<u8>,
}

#[derive(Debug)]
pub enum ProgressiveError {
    Io(io::Error),
    BadBlock(String),
    Rlgr(rlgr::RlgrError),
    BadRegion(String),
}

impl std::fmt::Display for ProgressiveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(e) => write!(f, "io error: {e}"),
            Self::BadBlock(s) => write!(f, "bad block: {s}"),
            Self::Rlgr(e) => write!(f, "rlgr error: {e:?}"),
            Self::BadRegion(s) => write!(f, "bad region: {s}"),
        }
    }
}

impl std::error::Error for ProgressiveError {}

impl From<io::Error> for ProgressiveError {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}
impl From<rlgr::RlgrError> for ProgressiveError {
    fn from(e: rlgr::RlgrError) -> Self {
        Self::Rlgr(e)
    }
}

/// MS-RDPRFX Progressive decoder. Per-channel state lives here:
/// SYNC seen, CONTEXT flags, scratch buffers used during tile decode.
pub struct ProgressiveDecoder {
    sync_seen: bool,
    context_flags: Option<u8>,
    /// Scratch i16 buffers for one tile's three planes. Boxed so we
    /// don't blow the stack in `decode`.
    work_y: Box<[i16; SUBBAND_LEN]>,
    work_cb: Box<[i16; SUBBAND_LEN]>,
    work_cr: Box<[i16; SUBBAND_LEN]>,
    /// IDWT temporary buffer.
    work_tmp: Box<[i16; SUBBAND_LEN]>,
}

impl ProgressiveDecoder {
    pub fn new() -> Self {
        Self {
            sync_seen: false,
            context_flags: None,
            work_y: Box::new([0; SUBBAND_LEN]),
            work_cb: Box::new([0; SUBBAND_LEN]),
            work_cr: Box::new([0; SUBBAND_LEN]),
            work_tmp: Box::new([0; SUBBAND_LEN]),
        }
    }

    /// Decode one Progressive PDU's bitmap_data, appending all decoded
    /// tiles to `out_tiles`. The same channel can carry many PDUs; state
    /// (sync_seen, context_flags) persists across calls.
    pub fn decode(
        &mut self,
        surface_id: u16,
        bitmap_data: &[u8],
        out_tiles: &mut Vec<DecodedTile>,
    ) -> Result<(), ProgressiveError> {
        let mut pos = 0usize;
        while pos + 6 <= bitmap_data.len() {
            let bt = u16_le(&bitmap_data[pos..]);
            let bl = u32_le(&bitmap_data[pos + 2..]) as usize;
            if bl < 6 || pos + bl > bitmap_data.len() {
                return Err(ProgressiveError::BadBlock(format!(
                    "block 0x{bt:04x} len={bl} overflows at offset {pos}, total {} bytes",
                    bitmap_data.len()
                )));
            }
            let payload = &bitmap_data[pos + 6..pos + bl];
            match bt {
                WBT_SYNC => self.parse_sync(payload)?,
                WBT_CONTEXT => self.parse_context(payload)?,
                WBT_FRAME_BEGIN => self.parse_frame_begin(payload)?,
                WBT_FRAME_END => debug!("Progressive: WBT_FRAME_END"),
                WBT_REGION => self.parse_region(surface_id, payload, out_tiles)?,
                other => warn!(
                    "Progressive: unexpected top-level block 0x{other:04x} ({} byte payload)",
                    payload.len()
                ),
            }
            pos += bl;
        }
        if pos != bitmap_data.len() {
            warn!(
                "Progressive: {} trailing bytes after blocks",
                bitmap_data.len() - pos
            );
        }
        Ok(())
    }

    fn parse_sync(&mut self, payload: &[u8]) -> Result<(), ProgressiveError> {
        if payload.len() < 6 {
            return Err(ProgressiveError::BadBlock(format!(
                "WBT_SYNC payload {} bytes < 6",
                payload.len()
            )));
        }
        let magic = u32_le(&payload[0..]);
        let ver = u16_le(&payload[4..]);
        if magic != SYNC_MAGIC {
            return Err(ProgressiveError::BadBlock(format!(
                "WBT_SYNC bad magic 0x{magic:08x} (want 0x{SYNC_MAGIC:08x})"
            )));
        }
        if ver != SYNC_VERSION {
            warn!("Progressive: SYNC version 0x{ver:04x} (expected 0x{SYNC_VERSION:04x})");
        }
        self.sync_seen = true;
        debug!("Progressive: SYNC ok");
        Ok(())
    }

    fn parse_context(&mut self, payload: &[u8]) -> Result<(), ProgressiveError> {
        if payload.len() < 4 {
            return Err(ProgressiveError::BadBlock(format!(
                "WBT_CONTEXT payload {} < 4",
                payload.len()
            )));
        }
        let ctx_id = payload[0];
        let tile_size = u16_le(&payload[1..]);
        let flags = payload[3];
        if tile_size != 64 {
            return Err(ProgressiveError::BadBlock(format!(
                "WBT_CONTEXT tileSize {tile_size} != 64"
            )));
        }
        self.context_flags = Some(flags);
        debug!("Progressive: CONTEXT ctxId={ctx_id} tileSize={tile_size} flags=0x{flags:02x}");
        Ok(())
    }

    fn parse_frame_begin(&mut self, payload: &[u8]) -> Result<(), ProgressiveError> {
        if payload.len() < 6 {
            return Err(ProgressiveError::BadBlock(format!(
                "WBT_FRAME_BEGIN payload {} < 6",
                payload.len()
            )));
        }
        let frame_idx = u32_le(&payload[0..]);
        let region_count = u16_le(&payload[4..]);
        debug!("Progressive: FRAME_BEGIN idx={frame_idx} regions={region_count}");
        Ok(())
    }

    fn parse_region(
        &mut self,
        surface_id: u16,
        payload: &[u8],
        out_tiles: &mut Vec<DecodedTile>,
    ) -> Result<(), ProgressiveError> {
        // 12-byte fixed header.
        if payload.len() < 12 {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION header {} < 12",
                payload.len()
            )));
        }
        let tile_size = payload[0];
        let num_rects = u16_le(&payload[1..]);
        let num_quant = payload[3];
        let num_prog_quant = payload[4];
        let flags = payload[5];
        let _num_tiles = u16_le(&payload[6..]);
        let tile_data_size = u32_le(&payload[8..]) as usize;
        if tile_size != 64 {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION tileSize {tile_size} != 64"
            )));
        }
        let extrapolate = (flags & RFX_DWT_REDUCE_EXTRAPOLATE) != 0;

        // Parse variable-length arrays after the fixed header.
        let mut p = 12usize;
        // rects: numRects * 8 (we don't actually need them for tile
        // decode — tile xIdx/yIdx + tile flags carry placement).
        let rects_len = num_rects as usize * 8;
        if p + rects_len > payload.len() {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION short for {num_rects} rects"
            )));
        }
        p += rects_len;

        // quants: numQuant * 5 bytes
        let quants_len = num_quant as usize * 5;
        if p + quants_len > payload.len() {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION short for {num_quant} quants"
            )));
        }
        let mut quants: Vec<Quant> = Vec::with_capacity(num_quant as usize);
        for _ in 0..num_quant {
            let mut b = [0u8; 5];
            b.copy_from_slice(&payload[p..p + 5]);
            quants.push(Quant::read(b));
            p += 5;
        }

        // progQuants: numProgQuant * (1 + 3*5) = 16 bytes
        let pq_len = num_prog_quant as usize * 16;
        if p + pq_len > payload.len() {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION short for {num_prog_quant} progQuants"
            )));
        }
        let mut prog_quants: Vec<ProgQuant> = Vec::with_capacity(num_prog_quant as usize);
        for _ in 0..num_prog_quant {
            let quality = payload[p];
            let mut yb = [0u8; 5];
            yb.copy_from_slice(&payload[p + 1..p + 6]);
            let mut cbb = [0u8; 5];
            cbb.copy_from_slice(&payload[p + 6..p + 11]);
            let mut crb = [0u8; 5];
            crb.copy_from_slice(&payload[p + 11..p + 16]);
            prog_quants.push(ProgQuant {
                quality,
                y: Quant::read(yb),
                cb: Quant::read(cbb),
                cr: Quant::read(crb),
            });
            p += 16;
        }

        // Tile blocks
        if p + tile_data_size > payload.len() {
            return Err(ProgressiveError::BadRegion(format!(
                "REGION short for tileData (need {tile_data_size}, have {})",
                payload.len() - p
            )));
        }
        let tiles_end = p + tile_data_size;
        debug!(
            "Progressive: REGION extrapolate={extrapolate} numQuant={num_quant} numProgQuant={num_prog_quant} tileData={tile_data_size}"
        );
        while p + 6 <= tiles_end {
            let tt = u16_le(&payload[p..]);
            let tl = u32_le(&payload[p + 2..]) as usize;
            if tl < 6 || p + tl > tiles_end {
                return Err(ProgressiveError::BadRegion(format!(
                    "tile block 0x{tt:04x} len={tl} overflows at offset {p}/{tiles_end}"
                )));
            }
            let tpayload = &payload[p + 6..p + tl];
            match tt {
                WBT_TILE_SIMPLE | WBT_TILE_FIRST => {
                    self.decode_tile_first_or_simple(
                        surface_id,
                        tt == WBT_TILE_SIMPLE,
                        tpayload,
                        &quants,
                        &prog_quants,
                        extrapolate,
                        out_tiles,
                    )?;
                }
                WBT_TILE_UPGRADE => {
                    debug!(
                        "Progressive: WBT_TILE_UPGRADE skipped ({} byte payload)",
                        tpayload.len()
                    );
                }
                other => warn!("Progressive: unknown tile block 0x{other:04x}"),
            }
            p += tl;
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn decode_tile_first_or_simple(
        &mut self,
        surface_id: u16,
        simple: bool,
        payload: &[u8],
        quants: &[Quant],
        prog_quants: &[ProgQuant],
        extrapolate: bool,
        out_tiles: &mut Vec<DecodedTile>,
    ) -> Result<(), ProgressiveError> {
        // SIMPLE header: 16 bytes; FIRST: 17 bytes (extra `quality`).
        let hdr = if simple { 16 } else { 17 };
        if payload.len() < hdr {
            return Err(ProgressiveError::BadBlock(format!(
                "tile header {} < {hdr}",
                payload.len()
            )));
        }
        let quant_idx_y = payload[0];
        let quant_idx_cb = payload[1];
        let quant_idx_cr = payload[2];
        let x_idx = u16_le(&payload[3..]);
        let y_idx = u16_le(&payload[5..]);
        let tile_flags = payload[7];
        let (quality, mut p) = if simple {
            (0xFFu8, 8usize)
        } else {
            (payload[8], 9usize)
        };
        let y_len = u16_le(&payload[p..]) as usize;
        let cb_len = u16_le(&payload[p + 2..]) as usize;
        let cr_len = u16_le(&payload[p + 4..]) as usize;
        let _tail_len = u16_le(&payload[p + 6..]) as usize;
        p += 8;
        if p + y_len + cb_len + cr_len > payload.len() {
            return Err(ProgressiveError::BadBlock(format!(
                "tile component sizes overflow: y={y_len} cb={cb_len} cr={cr_len} body={}",
                payload.len() - p
            )));
        }
        let y_data = &payload[p..p + y_len];
        let cb_data = &payload[p + y_len..p + y_len + cb_len];
        let cr_data = &payload[p + y_len + cb_len..p + y_len + cb_len + cr_len];

        // Look up base + progressive quant (per-channel).
        if quant_idx_y as usize >= quants.len()
            || quant_idx_cb as usize >= quants.len()
            || quant_idx_cr as usize >= quants.len()
        {
            return Err(ProgressiveError::BadBlock(format!(
                "tile quantIdx out of range: y={quant_idx_y} cb={quant_idx_cb} cr={quant_idx_cr} numQuant={}",
                quants.len()
            )));
        }
        let q_y = quants[quant_idx_y as usize];
        let q_cb = quants[quant_idx_cb as usize];
        let q_cr = quants[quant_idx_cr as usize];

        // quality = 0xFF means "full quality" (no progressive refinement
        // overlay). We still compute shift = quant + progQuant - 1, and
        // for full quality progQuant is the all-zeros entry.
        let prog: ProgQuant = if quality == 0xFF {
            ProgQuant::default()
        } else {
            let idx = quality as usize;
            if idx >= prog_quants.len() {
                return Err(ProgressiveError::BadBlock(format!(
                    "tile quality {quality} >= numProgQuant {}",
                    prog_quants.len()
                )));
            }
            ProgQuant {
                quality: prog_quants[idx].quality,
                y: prog_quants[idx].y,
                cb: prog_quants[idx].cb,
                cr: prog_quants[idx].cr,
            }
        };

        let coeff_diff = (tile_flags & RFX_TILE_DIFFERENCE) != 0;
        if coeff_diff {
            // RFX_TILE_DIFFERENCE means the encoded coefficients are a
            // delta against the previously-decoded same tile (UPGRADE
            // path, or a multi-pass FIRST). Without a saved per-tile
            // sign+current buffer this would produce wrong output, so
            // skip rather than paint garbage. UPGRADE support → future.
            debug!(
                "Progressive: tile (xIdx={x_idx},yIdx={y_idx}) flags=0x{tile_flags:02x} TILE_DIFFERENCE set, skipping"
            );
            return Ok(());
        }

        // Compute per-band shift = quant + progQuant. The component
        // decoder applies (shift - 1) per FreeRDP `lsub(&shift, 1)`.
        let shift_y = q_y.add(&prog.y);
        let shift_cb = q_cb.add(&prog.cb);
        let shift_cr = q_cr.add(&prog.cr);

        decode_component(
            &mut *self.work_y,
            &mut *self.work_tmp,
            y_data,
            &shift_y,
            extrapolate,
        )?;
        decode_component(
            &mut *self.work_cb,
            &mut *self.work_tmp,
            cb_data,
            &shift_cb,
            extrapolate,
        )?;
        decode_component(
            &mut *self.work_cr,
            &mut *self.work_tmp,
            cr_data,
            &shift_cr,
            extrapolate,
        )?;

        // Convert i16 YCbCr -> RGBA8888. The values out of IDWT are in
        // signed YCbCr space (~ -128..127 for 8-bit content).
        let mut rgba = vec![0u8; 64 * 64 * 4];
        ycbcr_i16_to_rgba(&self.work_y, &self.work_cb, &self.work_cr, &mut rgba);

        out_tiles.push(DecodedTile {
            surface_id,
            x: x_idx.saturating_mul(64),
            y: y_idx.saturating_mul(64),
            rgba,
        });
        Ok(())
    }
}

impl Default for ProgressiveDecoder {
    fn default() -> Self {
        Self::new()
    }
}

// ---- one-plane decode ----

/// Decode one Y/Cb/Cr plane: RLGR1 -> per-band lShift -> IDWT.
/// `buffer` ends up as 64×64 i16 pixel values stored row-major.
fn decode_component(
    buffer: &mut [i16; SUBBAND_LEN],
    temp: &mut [i16; SUBBAND_LEN],
    encoded: &[u8],
    shift: &Quant,
    extrapolate: bool,
) -> Result<(), ProgressiveError> {
    // Step 1: RLGR1-decode 4096 i16 coefficients.
    rlgr::decode(EntropyAlgorithm::Rlgr1, encoded, &mut buffer[..])?;

    // Step 2: per-subband left-shift. FreeRDP applies (shift-1):
    //
    //     progressive_rfx_quant_lsub(&shift, 1);
    //
    // So we shift each subband by max(0, qBand-1).
    if extrapolate {
        // Extrapolate band layout (from progressive.c band table):
        // HL1 0..1023 (31x33), LH1 1023..2046 (33x31), HH1 2046..3007 (31x31),
        // HL2 3007..3279 (16x17), LH2 3279..3551 (17x16), HH2 3551..3807 (16x16),
        // HL3 3807..3879 (8x9),  LH3 3879..3951 (9x8),  HH3 3951..4015 (8x8),
        // LL3 4015..4096 (9x9).
        lshift_block(&mut buffer[0..1023], shift.hl1);
        lshift_block(&mut buffer[1023..2046], shift.lh1);
        lshift_block(&mut buffer[2046..3007], shift.hh1);
        lshift_block(&mut buffer[3007..3279], shift.hl2);
        lshift_block(&mut buffer[3279..3551], shift.lh2);
        lshift_block(&mut buffer[3551..3807], shift.hh2);
        lshift_block(&mut buffer[3807..3879], shift.hl3);
        lshift_block(&mut buffer[3879..3951], shift.lh3);
        lshift_block(&mut buffer[3951..4015], shift.hh3);
        lshift_block(&mut buffer[4015..4096], shift.ll3);
    } else {
        // Classic non-extrapolate layout (matches classic RFX):
        // HL1 0..1024, LH1 1024..2048, HH1 2048..3072,
        // HL2 3072..3328, LH2 3328..3584, HH2 3584..3840,
        // HL3 3840..3904, LH3 3904..3968, HH3 3968..4032,
        // LL3 4032..4096.
        // FreeRDP also runs differential decode on LL3 first.
        differential_decode(&mut buffer[4032..4096]);
        lshift_block(&mut buffer[0..1024], shift.hl1);
        lshift_block(&mut buffer[1024..2048], shift.lh1);
        lshift_block(&mut buffer[2048..3072], shift.hh1);
        lshift_block(&mut buffer[3072..3328], shift.hl2);
        lshift_block(&mut buffer[3328..3584], shift.lh2);
        lshift_block(&mut buffer[3584..3840], shift.hh2);
        lshift_block(&mut buffer[3840..3904], shift.hl3);
        lshift_block(&mut buffer[3904..3968], shift.lh3);
        lshift_block(&mut buffer[3968..4032], shift.hh3);
        lshift_block(&mut buffer[4032..4096], shift.ll3);
    }

    // Step 3: inverse DWT.
    if extrapolate {
        idwt_extrapolate(buffer, temp);
    } else {
        idwt_classic(buffer, temp);
    }
    Ok(())
}

/// `lShiftC` from FreeRDP primitives: each i16 in `block` shifted left
/// by `(qBand - 1)` saturating at 0. Quant values are in [6,15] per
/// MS-RDPRFX so (qBand - 1) is in [5, 14].
fn lshift_block(block: &mut [i16], q_band: u8) {
    let factor = i16::from(q_band).saturating_sub(1);
    if factor <= 0 {
        return;
    }
    for v in block.iter_mut() {
        *v = ((*v as i32) << factor) as i16;
    }
}

/// Differential decode (running sum) used on classic-mode LL3 only.
/// FreeRDP `rfx_differential_decode`.
fn differential_decode(block: &mut [i16]) {
    for i in 1..block.len() {
        block[i] = block[i].wrapping_add(block[i - 1]);
    }
}

// ---- IDWT, classic mode (non-extrapolate) ----
//
// Identical layout to classic RFX. We delegate to ironrdp_graphics::dwt
// rather than reimplementing.

fn idwt_classic(buffer: &mut [i16; SUBBAND_LEN], temp: &mut [i16; SUBBAND_LEN]) {
    ironrdp_graphics::dwt::decode(&mut buffer[..], &mut temp[..]);
}

// ---- IDWT, extrapolate mode ----
//
// Asymmetric L/H subband counts:
//   level 1: nBandL=33, nBandH=31  -> output 64x64
//   level 2: nBandL=17, nBandH=16  -> output 33x33
//   level 3: nBandL=9,  nBandH=8   -> output 17x17
//
// Buffer layout (per FreeRDP comment):
//   Level 3 starts at offset 3807: HL3 (72), LH3 (72), HH3 (64), LL3 (81)
//   Level 2 starts at offset 3007: HL2 (272), LH2 (272), HH2 (256), LL2 (289)
//   Level 1 starts at offset 0:    HL1 (1023), LH1 (1023), HH1 (961), LL1 (1089)
//
// Each level reads its 4 subbands at fixed offsets into a temp area, runs
// 1-D IDWT in horizontal then vertical, and writes (nBandL+nBandH) ×
// (nBandL+nBandH) reconstructed values back at the start of its block.
// The reconstructed image at level n becomes LL of level (n-1).

fn idwt_extrapolate(buffer: &mut [i16; SUBBAND_LEN], temp: &mut [i16; SUBBAND_LEN]) {
    idwt_extrapolate_level(&mut buffer[3807..], temp, 9, 8);
    idwt_extrapolate_level(&mut buffer[3007..], temp, 17, 16);
    idwt_extrapolate_level(&mut buffer[0..], temp, 33, 31);
}

/// One IDWT level. `buf` is the slice starting at the level's HL band.
/// Layout (relative to buf[0]):
///   HL: [0 .. nh*nl)        cols=nh, rows=nl
///   LH: [nh*nl .. 2*nh*nl)  cols=nl, rows=nh
///   HH: [2*nh*nl .. 2*nh*nl + nh*nh) cols=nh, rows=nh
///   LL: [...     .. ...     + nl*nl) cols=nl, rows=nl
/// Output goes back to buf[0..(nl+nh)*(nl+nh)] row-major.
fn idwt_extrapolate_level(
    buf: &mut [i16],
    temp: &mut [i16; SUBBAND_LEN],
    n_l: usize,
    n_h: usize,
) {
    let dst_step = n_l + n_h;
    // Subband offsets.
    let hl_off = 0usize;
    let lh_off = n_h * n_l;
    let hh_off = 2 * n_h * n_l;
    let ll_off = 2 * n_h * n_l + n_h * n_h;

    // Temp layout: L (nL rows × dst_step cols) then H (nH rows × dst_step cols).
    // Both written in horizontal pass, consumed in vertical pass.
    let l_off = 0usize;
    let h_off = n_l * dst_step;
    let total_temp = (n_l + n_h) * dst_step;
    debug_assert!(total_temp <= SUBBAND_LEN);
    for v in temp[..total_temp].iter_mut() {
        *v = 0;
    }

    // Horizontal pass (LL + HL -> L). Iterates dst_count=n_l rows.
    // Each row reads n_l L samples (from LL) and n_h H samples (from
    // HL) and writes n_l + n_h reconstructed columns.
    {
        let (ll_lo, hl_lo) = (ll_off, hl_off);
        // Sub-borrow safety: we read from buf[hl_off..ll_off+...] and
        // write into temp.
        idwt_horizontal_pass(
            &buf[ll_lo..ll_lo + n_l * n_l],
            n_l,
            &buf[hl_lo..hl_lo + n_h * n_l],
            n_h,
            &mut temp[l_off..l_off + n_l * dst_step],
            dst_step,
            n_l,
            n_h,
            n_l,
        );
    }
    // Horizontal pass (LH + HH -> H). dst_count = n_h rows.
    {
        let (lh_lo, hh_lo) = (lh_off, hh_off);
        idwt_horizontal_pass(
            &buf[lh_lo..lh_lo + n_l * n_h],
            n_l,
            &buf[hh_lo..hh_lo + n_h * n_h],
            n_h,
            &mut temp[h_off..h_off + n_h * dst_step],
            dst_step,
            n_l,
            n_h,
            n_h,
        );
    }
    // Vertical pass (L + H -> reconstruction). Writes back to buf[0..].
    {
        // Take a fresh borrow split: we need both halves of temp as immutable.
        let (l_part, h_part) = temp[..total_temp].split_at(n_l * dst_step);
        idwt_vertical_pass(
            l_part,
            dst_step,
            h_part,
            dst_step,
            &mut buf[..(n_l + n_h) * dst_step],
            dst_step,
            n_l,
            n_h,
            n_l + n_h,
        );
    }
}

/// Generic 1-D IDWT pass (port of FreeRDP `progressive_rfx_idwt_x`).
///
/// Reads `dst_count` rows of (n_low + n_high) interleaved samples,
/// reconstructing the merged signal into `pDst`. The structure operates
/// row by row — for each of `dst_count` rows, traverse `n_high` H
/// samples and `n_low` L samples and emit `n_low + n_high` outputs.
#[allow(clippy::too_many_arguments)]
fn idwt_horizontal_pass(
    p_low: &[i16],
    low_step: usize,
    p_high: &[i16],
    high_step: usize,
    p_dst: &mut [i16],
    dst_step: usize,
    n_low_count: usize,
    n_high_count: usize,
    dst_count: usize,
) {
    for i in 0..dst_count {
        let l_row = &p_low[i * low_step..i * low_step + n_low_count];
        let h_row = &p_high[i * high_step..i * high_step + n_high_count];
        let dst_row = &mut p_dst[i * dst_step..i * dst_step + n_low_count + n_high_count];
        idwt_1d(l_row, h_row, dst_row, n_low_count, n_high_count);
    }
}

/// Generic 1-D IDWT pass (port of FreeRDP `progressive_rfx_idwt_y`).
///
/// Like `_horizontal_pass` but iterates `dst_count` columns rather than
/// rows — pL/pH/pX advance by 1 each iteration instead of by step.
#[allow(clippy::too_many_arguments)]
fn idwt_vertical_pass(
    p_low: &[i16],
    low_step: usize,
    p_high: &[i16],
    high_step: usize,
    p_dst: &mut [i16],
    dst_step: usize,
    n_low_count: usize,
    n_high_count: usize,
    dst_count: usize,
) {
    // Walk one column at a time: column i contributes column i to L,
    // column i to H, column i to dst. Within each column, walk down
    // n_high or n_low rows.
    let mut l_col_buf = vec![0i16; n_low_count];
    let mut h_col_buf = vec![0i16; n_high_count];
    let mut d_col_buf = vec![0i16; n_low_count + n_high_count];
    for i in 0..dst_count {
        // gather column i
        for r in 0..n_low_count {
            l_col_buf[r] = p_low[r * low_step + i];
        }
        for r in 0..n_high_count {
            h_col_buf[r] = p_high[r * high_step + i];
        }
        idwt_1d(
            &l_col_buf,
            &h_col_buf,
            &mut d_col_buf,
            n_low_count,
            n_high_count,
        );
        // scatter column i
        for r in 0..(n_low_count + n_high_count) {
            p_dst[r * dst_step + i] = d_col_buf[r];
        }
    }
}

/// Core 1-D IDWT reconstruction (port of FreeRDP's per-row inner loop
/// in `progressive_rfx_idwt_x`). Reads `n_low` L samples + `n_high` H
/// samples and writes `n_low + n_high` reconstructed values.
fn idwt_1d(l: &[i16], h: &[i16], dst: &mut [i16], n_low: usize, n_high: usize) {
    debug_assert!(l.len() >= n_low);
    debug_assert!(h.len() >= n_high);
    debug_assert!(dst.len() >= n_low + n_high);
    if n_high == 0 {
        // Degenerate case (shouldn't occur for our levels).
        for i in 0..n_low {
            dst[i] = l[i];
        }
        return;
    }

    let mut h0 = h[0] as i32;
    let mut l0 = l[0] as i32;
    let mut x0 = clamp_i16(l0 - h0);
    let mut x2 = clamp_i16(l0 - h0);
    let mut wpos = 0usize;
    let mut lpos = 1usize; // next L to read
    let mut hpos = 1usize; // next H to read

    for _ in 0..n_high.saturating_sub(1) {
        let h1 = h[hpos] as i32;
        hpos += 1;
        l0 = l[lpos] as i32;
        lpos += 1;
        x2 = clamp_i16(l0 - ((h0 + h1) / 2));
        let x1 = clamp_i16(((x0 as i32 + x2 as i32) / 2) + (2 * h0));
        dst[wpos] = x0;
        dst[wpos + 1] = x1;
        wpos += 2;
        x0 = x2;
        h0 = h1;
    }

    // Tail handling — exactly mirrors the FreeRDP boundary cases.
    if n_low <= n_high + 1 {
        if n_low <= n_high {
            // Two more outputs from the boundary.
            dst[wpos] = x2;
            dst[wpos + 1] = clamp_i16(x2 as i32 + 2 * h0);
        } else {
            l0 = l[lpos] as i32;
            // (lpos consumed; not needed further)
            x0 = clamp_i16(l0 - h0);
            dst[wpos] = x2;
            dst[wpos + 1] = clamp_i16(((x0 as i32 + x2 as i32) / 2) + 2 * h0);
            dst[wpos + 2] = x0;
        }
    } else {
        l0 = l[lpos] as i32;
        lpos += 1;
        x0 = clamp_i16(l0 - (h0 / 2));
        dst[wpos] = x2;
        dst[wpos + 1] = clamp_i16(((x0 as i32 + x2 as i32) / 2) + 2 * h0);
        dst[wpos + 2] = x0;
        let l_next = l[lpos] as i32;
        dst[wpos + 3] = clamp_i16((x0 as i32 + l_next) / 2);
    }
}

fn clamp_i16(v: i32) -> i16 {
    v.clamp(i16::MIN as i32, i16::MAX as i32) as i16
}

// ---- color conversion ----

/// Convert three 64×64 i16 YCbCr planes (encoder-format 11.5 fixed
/// point: pixel value × 32, centred at 0) to a 64×64 RGBA8888 buffer.
///
/// Port of FreeRDP `general_yCbCrToRGB_16s8u_P3AC4R_general` from
/// `libfreerdp/primitives/prim_colors.c` at `divisor=16`:
///
/// ```text
///   yp = (Y + 4096) << 16          // Q16 of (Y + 4096)
///   R = ((yp + Cr * 91916) >> 16) >> 5
///   G = ((yp - Cb * 22527 - Cr * 46819) >> 16) >> 5
///   B = ((yp + Cb * 115992) >> 16) >> 5
/// ```
///
/// 91916/65536 ≈ 1.4025 (BT.601 R-from-Cr), 46819/65536 ≈ 0.7144
/// (G-from-Cr), 22527/65536 ≈ 0.3437 (G-from-Cb), 115992/65536 ≈ 1.7700
/// (B-from-Cb).
fn ycbcr_i16_to_rgba(y: &[i16; SUBBAND_LEN], cb: &[i16; SUBBAND_LEN], cr: &[i16; SUBBAND_LEN], rgba: &mut [u8]) {
    debug_assert!(rgba.len() >= 64 * 64 * 4);
    for i in 0..64 * 64 {
        let yv = i32::from(y[i]);
        let cbv = i32::from(cb[i]);
        let crv = i32::from(cr[i]);
        let yp = (yv + 4096) << 16;
        let r = (((yp + crv * 91916) >> 16) >> 5).clamp(0, 255) as u8;
        let g = (((yp - cbv * 22527 - crv * 46819) >> 16) >> 5).clamp(0, 255) as u8;
        let b = (((yp + cbv * 115992) >> 16) >> 5).clamp(0, 255) as u8;
        let o = i * 4;
        rgba[o] = r;
        rgba[o + 1] = g;
        rgba[o + 2] = b;
        rgba[o + 3] = 0xFF;
    }
}

// ---- small helpers ----

fn u16_le(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}
fn u32_le(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Quant byte 0x66, 0x76, 0x88, 0x99, 0xa9 — all 5 wire bytes of
    /// the captured PDU's lone quant. Verify decoding picks the right
    /// nibbles.
    #[test]
    fn quant_decode_matches_freerdp_ordering() {
        let q = Quant::read([0x66, 0x76, 0x88, 0x99, 0xa9]);
        assert_eq!(q.ll3, 6);
        assert_eq!(q.hl3, 6);
        assert_eq!(q.lh3, 6);
        assert_eq!(q.hh3, 7);
        assert_eq!(q.hl2, 8);
        assert_eq!(q.lh2, 8);
        assert_eq!(q.hh2, 9);
        assert_eq!(q.hl1, 9);
        assert_eq!(q.lh1, 9);
        assert_eq!(q.hh1, 10);
    }

    #[test]
    fn parser_handles_captured_first_pdu() {
        // Synthetic minimal PDU: SYNC + CONTEXT + FRAME_BEGIN +
        // empty REGION + FRAME_END.
        let mut pdu = Vec::new();
        // SYNC
        pdu.extend_from_slice(&WBT_SYNC.to_le_bytes());
        pdu.extend_from_slice(&12u32.to_le_bytes());
        pdu.extend_from_slice(&SYNC_MAGIC.to_le_bytes());
        pdu.extend_from_slice(&SYNC_VERSION.to_le_bytes());
        // CONTEXT
        pdu.extend_from_slice(&WBT_CONTEXT.to_le_bytes());
        pdu.extend_from_slice(&10u32.to_le_bytes());
        pdu.extend_from_slice(&[0u8, 64, 0, 0x01]); // ctxId, tileSize=64, flags=0x01
        // FRAME_BEGIN
        pdu.extend_from_slice(&WBT_FRAME_BEGIN.to_le_bytes());
        pdu.extend_from_slice(&12u32.to_le_bytes());
        pdu.extend_from_slice(&0u32.to_le_bytes()); // frameIndex
        pdu.extend_from_slice(&0u16.to_le_bytes()); // regionCount=0
        // FRAME_END
        pdu.extend_from_slice(&WBT_FRAME_END.to_le_bytes());
        pdu.extend_from_slice(&6u32.to_le_bytes());
        let mut dec = ProgressiveDecoder::new();
        let mut tiles = Vec::new();
        dec.decode(0, &pdu, &mut tiles).unwrap();
        assert!(dec.sync_seen);
        assert_eq!(dec.context_flags, Some(0x01));
        assert!(tiles.is_empty());
    }

    #[test]
    fn idwt_classic_round_trip_zero_input() {
        // All-zero input should produce all-zero output and not panic.
        let mut buf = Box::new([0i16; SUBBAND_LEN]);
        let mut tmp = Box::new([0i16; SUBBAND_LEN]);
        idwt_classic(&mut buf, &mut tmp);
        assert!(buf.iter().all(|&v| v == 0));
    }

    #[test]
    fn idwt_extrapolate_zero_input_no_panic() {
        let mut buf = Box::new([0i16; SUBBAND_LEN]);
        let mut tmp = Box::new([0i16; SUBBAND_LEN]);
        idwt_extrapolate(&mut buf, &mut tmp);
        assert!(buf.iter().all(|&v| v == 0));
    }

    #[test]
    fn lshift_block_handles_zero_factor() {
        let mut b = [10i16, 20, 30];
        lshift_block(&mut b, 1); // factor = 0
        assert_eq!(b, [10, 20, 30]);
        lshift_block(&mut b, 3); // factor = 2
        assert_eq!(b, [40, 80, 120]);
    }

    #[test]
    fn differential_decode_running_sum() {
        let mut b = [1i16, 2, 3, 4];
        differential_decode(&mut b);
        assert_eq!(b, [1, 3, 6, 10]);
    }
}
