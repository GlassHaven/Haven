//! ClearCodec (MS-RDPEGFX 2.2.5) decoder.
//!
//! ClearCodec is the codec Windows uses overwhelmingly for static desktop UI
//! content: text, window chrome, taskbar. Without it, EGFX surfaces stay
//! blank for any modern Windows server.
//!
//! Wire format (per packet):
//!
//! ```text
//!   u8  glyphFlags    bits: 0x01 GLYPH_INDEX, 0x02 GLYPH_HIT, 0x04 CACHE_RESET
//!   u8  seqNumber     0..=255 wrapping; first packet seeds the counter
//!   [GLYPH_INDEX set] u16 glyphIndex
//!   [GLYPH_INDEX|HIT] (the two together => copy glyph from cache, done)
//!   [composition payload, if remaining >= 12]
//!     u32 residualByteCount
//!     u32 bandsByteCount
//!     u32 subcodecByteCount
//!     [residual data]
//!     [bands data]
//!     [subcodecs data]
//! ```
//!
//! Three encodings carry pixels:
//!
//! * **Residual** — `(B,G,R, runLengthFactor=u8)` records, one stretched
//!   colour per record. `runLengthFactor==0xFF` extends to u16, `==0xFFFF`
//!   extends to u32. Pixels stream out in row-major order across the whole
//!   tile, then get blitted into place.
//! * **Bands** — column-stripe encoding. Each band is a horizontal range
//!   `[xStart..xEnd]` over rows `[yStart..yEnd]` with a background colour.
//!   For every column in that range a `vBarHeader` u16 picks one of three
//!   modes: a hit in `vbar_cache`, a hit in `short_vbar_cache` (with a new
//!   `yOn` offset to compose the column from short pixels + background), or
//!   a fresh "short" miss that reads new BGR triplets.
//! * **Subcodecs** — per-region records `(xStart u16, yStart u16, w u16,
//!   h u16, bitmapDataByteCount u32, subcodecId u8)`. We support id=0
//!   (uncompressed BGR24) and id=2 (RLEX palette+runs). NSCodec (id=1) is
//!   logged + skipped — Windows desktops don't emit it for typical UI.
//!
//! Caches:
//!
//! * `glyph_cache` — up to 4000 glyph tiles. Filled when a packet has
//!   `GLYPH_INDEX` set (and not `GLYPH_HIT`); replayed when both bits are
//!   set. Cleared by `reset()` (called on `ResetGraphics`, but per spec the
//!   ClearCodec context survives `ResetGraphics`).
//! * `short_vbar_cache` (16384 entries) and `vbar_cache` (32768 entries) —
//!   ring buffers used while decoding bands. CACHE_RESET (flag 0x04) zeros
//!   the cursors so the next miss writes to slot 0.
//!
//! Pixels in caches are stored as RGBA8888 to match `Surface::pixels` so
//! the eventual blit into the surface is a straight memcpy.
//!
//! Output: `decompress()` returns a `Vec<u8>` RGBA tile of size
//! `width * height * 4`. The caller blits it into the surface at the
//! `WireToSurface1Pdu::destination_rectangle` origin.
//!
//! Reference: FreeRDP `libfreerdp/codec/clear.c` (Apache 2.0). Algorithm
//! ported, code is original.

use std::collections::HashMap;

use log::warn;

const FLAG_GLYPH_INDEX: u8 = 0x01;
const FLAG_GLYPH_HIT: u8 = 0x02;
const FLAG_CACHE_RESET: u8 = 0x04;

const GLYPH_CACHE_SIZE: usize = 4000;
const VBAR_CACHE_SIZE: u16 = 32_768;
const SHORT_VBAR_CACHE_SIZE: u16 = 16_384;

/// Bytes per output pixel (RGBA8888).
const BPP: usize = 4;

/// 8-bit-mask lookup for `nbits`-wide fields. `MASK[n]` = `(1 << n) - 1`.
const MASK_8: [u8; 9] = [0x00, 0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF];

/// `LOG2_FLOOR[n] = floor(log2(n))` for `n >= 1`, defined as 0 for `n == 0`.
/// Used only for `paletteCount - 1` so the input is in 0..=126.
const LOG2_FLOOR: [u8; 128] = {
    let mut t = [0u8; 128];
    let mut i = 1usize;
    while i < 128 {
        let mut v = i;
        let mut log = 0u8;
        while v > 1 {
            v >>= 1;
            log += 1;
        }
        t[i] = log;
        i += 1;
    }
    t
};

#[derive(Debug)]
pub enum ClearError {
    Short { need: usize, have: usize },
    Malformed(&'static str),
    Sequence { got: u8, expected: u8 },
}

impl std::fmt::Display for ClearError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClearError::Short { need, have } => {
                write!(f, "clearcodec: short read (need {need} bytes, have {have})")
            }
            ClearError::Malformed(msg) => write!(f, "clearcodec: malformed payload: {msg}"),
            ClearError::Sequence { got, expected } => write!(
                f,
                "clearcodec: sequence number out of order (got {got}, expected {expected})"
            ),
        }
    }
}

impl std::error::Error for ClearError {}

type Result<T> = std::result::Result<T, ClearError>;

#[derive(Default, Clone)]
struct VBarEntry {
    /// RGBA8888 column pixels, length = `count * BPP`.
    pixels: Vec<u8>,
    /// Logical pixel count (rows in the column).
    count: u32,
}

pub struct ClearDecoder {
    /// Sequence counter; first non-zero packet seeds it, subsequent packets
    /// must increment by 1 mod 256 or we abort.
    seq_number: u32,
    /// Glyph cache, indexed by glyph_index 0..4000.
    glyph_cache: HashMap<u16, Vec<u8>>,
    /// Two ring buffers (cursors wrap modulo capacity, cache_reset zeros
    /// them). Indexing in cache_hit branches uses the wire-supplied index
    /// directly into the *current* contents — gaps before the cursor are
    /// just zero-initialised.
    vbar_cache: HashMap<u16, VBarEntry>,
    short_vbar_cache: HashMap<u16, VBarEntry>,
    vbar_cursor: u16,
    short_vbar_cursor: u16,
}

impl ClearDecoder {
    pub fn new() -> Self {
        Self {
            seq_number: 0,
            glyph_cache: HashMap::new(),
            vbar_cache: HashMap::new(),
            short_vbar_cache: HashMap::new(),
            vbar_cursor: 0,
            short_vbar_cursor: 0,
        }
    }

    /// Spec note: ClearCodec context survives `ResetGraphics`. This is here
    /// only to be called on hard reconnects; do not wire it to ResetGraphics.
    #[allow(dead_code)]
    pub fn reset_hard(&mut self) {
        self.seq_number = 0;
        self.glyph_cache.clear();
        self.vbar_cache.clear();
        self.short_vbar_cache.clear();
        self.vbar_cursor = 0;
        self.short_vbar_cursor = 0;
    }

    /// Decode one ClearCodec payload into a `width * height` RGBA8888 tile.
    pub fn decompress(&mut self, payload: &[u8], width: u32, height: u32) -> Result<Vec<u8>> {
        if width == 0 || height == 0 || width > 0xFFFF || height > 0xFFFF {
            return Err(ClearError::Malformed("invalid tile dimensions"));
        }
        let pixel_count = (width as usize)
            .checked_mul(height as usize)
            .ok_or(ClearError::Malformed("tile dimension overflow"))?;
        let mut tile = vec![0u8; pixel_count * BPP];

        let mut c = Cursor::new(payload);
        let glyph_flags = c.u8()?;
        let seq_number = c.u8()?;

        // First packet seeds the counter; subsequent packets must increment.
        if self.seq_number == 0 && seq_number != 0 {
            self.seq_number = u32::from(seq_number);
        }
        if u32::from(seq_number) != self.seq_number {
            return Err(ClearError::Sequence {
                got: seq_number,
                expected: self.seq_number as u8,
            });
        }
        self.seq_number = (self.seq_number + 1) % 256;

        if (glyph_flags & FLAG_CACHE_RESET) != 0 {
            // Spec: only the cursors are reset; entries are kept (FreeRDP
            // mirrors this: `clear_reset_vbar_storage(clear, FALSE)`).
            self.vbar_cursor = 0;
            self.short_vbar_cursor = 0;
        }

        // Glyph stage. May short-circuit (GLYPH_HIT replays from cache and
        // we're done), or capture the index so we cache the decoded result.
        let mut glyph_capture_index: Option<u16> = None;
        if (glyph_flags & FLAG_GLYPH_HIT) != 0 && (glyph_flags & FLAG_GLYPH_INDEX) == 0 {
            return Err(ClearError::Malformed("GLYPH_HIT without GLYPH_INDEX"));
        }
        if (glyph_flags & FLAG_GLYPH_INDEX) != 0 {
            if pixel_count > 1024 * 1024 {
                return Err(ClearError::Malformed("glyph too large"));
            }
            let glyph_index = c.u16()?;
            if usize::from(glyph_index) >= GLYPH_CACHE_SIZE {
                return Err(ClearError::Malformed("glyph_index out of range"));
            }
            if (glyph_flags & FLAG_GLYPH_HIT) != 0 {
                // Pure replay. Body must end here (or composition header
                // must be missing — checked below by remaining length).
                let glyph = self
                    .glyph_cache
                    .get(&glyph_index)
                    .ok_or(ClearError::Malformed("GLYPH_HIT references empty cache slot"))?;
                if glyph.len() != tile.len() {
                    return Err(ClearError::Malformed("cached glyph size mismatch"));
                }
                tile.copy_from_slice(glyph);
                return Ok(tile);
            }
            glyph_capture_index = Some(glyph_index);
        }

        // Composition payload header — 12 bytes if present.
        if c.remaining() < 12 {
            // Per spec this is OK only if the packet was a glyph replay
            // (GLYPH_INDEX|GLYPH_HIT both set), which we handled above.
            // Anything else here means a truncated payload.
            if (glyph_flags & (FLAG_GLYPH_INDEX | FLAG_GLYPH_HIT))
                == (FLAG_GLYPH_INDEX | FLAG_GLYPH_HIT)
            {
                return Ok(tile);
            }
            return Err(ClearError::Malformed("missing composition header"));
        }
        let residual_bytes = c.u32()? as usize;
        let bands_bytes = c.u32()? as usize;
        let subcodec_bytes = c.u32()? as usize;

        if residual_bytes > 0 {
            let chunk = c.slice(residual_bytes)?;
            decode_residual(chunk, width, height, &mut tile)?;
        }
        if bands_bytes > 0 {
            let chunk = c.slice(bands_bytes)?;
            self.decode_bands(chunk, width, height, &mut tile)?;
        }
        if subcodec_bytes > 0 {
            let chunk = c.slice(subcodec_bytes)?;
            decode_subcodecs(chunk, width, height, &mut tile)?;
        }

        if let Some(idx) = glyph_capture_index {
            self.glyph_cache.insert(idx, tile.clone());
        }
        Ok(tile)
    }

    fn decode_bands(
        &mut self,
        mut chunk: &[u8],
        nwidth: u32,
        nheight: u32,
        tile: &mut [u8],
    ) -> Result<()> {
        while !chunk.is_empty() {
            let mut c = Cursor::new(chunk);
            let x_start = c.u16()?;
            let x_end = c.u16()?;
            let y_start = c.u16()?;
            let y_end = c.u16()?;
            let cb = c.u8()?;
            let cg = c.u8()?;
            let cr = c.u8()?;
            if x_end < x_start || y_end < y_start {
                return Err(ClearError::Malformed("band: end < start"));
            }
            let v_bar_count = u32::from(x_end - x_start) + 1;
            let v_bar_height = u32::from(y_end - y_start) + 1;
            if v_bar_height > 52 {
                return Err(ClearError::Malformed("vbar height > 52"));
            }
            let bg = [cr, cg, cb, 0xFF];

            // Each column of the band: read header + maybe pixels, write the
            // composed column directly into the tile at (x_start+i, y_start..).
            for i in 0..v_bar_count {
                let v_bar_header = c.u16()?;
                let column_pixels =
                    self.resolve_vbar_column(&mut c, v_bar_header, v_bar_height, &bg)?;

                let dst_x = u32::from(x_start) + i;
                if dst_x >= nwidth {
                    continue;
                }
                let max_y = std::cmp::min(u32::from(y_start) + v_bar_height, nheight);
                for y in 0..(max_y - u32::from(y_start)) {
                    let dst_y = u32::from(y_start) + y;
                    let src_off = (y as usize) * BPP;
                    let dst_off = (dst_y as usize * nwidth as usize + dst_x as usize) * BPP;
                    if dst_off + BPP <= tile.len() && src_off + BPP <= column_pixels.len() {
                        tile[dst_off..dst_off + BPP]
                            .copy_from_slice(&column_pixels[src_off..src_off + BPP]);
                    }
                }
            }
            chunk = c.into_remaining();
        }
        Ok(())
    }

    /// Returns RGBA8888 column pixels of length `v_bar_height * BPP`,
    /// resolving vbar caches and writing back as needed.
    fn resolve_vbar_column(
        &mut self,
        c: &mut Cursor<'_>,
        v_bar_header: u16,
        v_bar_height: u32,
        bg: &[u8; 4],
    ) -> Result<Vec<u8>> {
        let kind = v_bar_header & 0xC000;
        let (short_pixels, vbar_y_on, build_long) = if kind == 0x4000 {
            // SHORT_VBAR_CACHE_HIT — reuse short pixels from cache, but read
            // a fresh yOn so the column composition (bg/short/bg) shifts.
            let idx = v_bar_header & 0x3FFF;
            let short = self
                .short_vbar_cache
                .get(&idx)
                .cloned()
                .ok_or(ClearError::Malformed("short_vbar cache miss on HIT"))?;
            let y_on = u32::from(c.u8()?);
            (Some(short), y_on, true)
        } else if kind == 0x0000 {
            // SHORT_VBAR_CACHE_MISS — read fresh BGR pixels, store them in
            // short_vbar_cache, then build a long entry from them.
            let y_on = u32::from(v_bar_header & 0xFF);
            let y_off = u32::from((v_bar_header >> 8) & 0x3F);
            if y_off < y_on {
                return Err(ClearError::Malformed("vbar yOff < yOn"));
            }
            let short_count = y_off - y_on;
            if short_count > 52 {
                return Err(ClearError::Malformed("short vbar count > 52"));
            }
            let mut pixels = Vec::with_capacity((short_count as usize) * BPP);
            for _ in 0..short_count {
                let b = c.u8()?;
                let g = c.u8()?;
                let r = c.u8()?;
                pixels.extend_from_slice(&[r, g, b, 0xFF]);
            }
            let entry = VBarEntry {
                pixels,
                count: short_count,
            };
            let slot = self.short_vbar_cursor;
            self.short_vbar_cache.insert(slot, entry.clone());
            self.short_vbar_cursor = (self.short_vbar_cursor + 1) % SHORT_VBAR_CACHE_SIZE;
            (Some(entry), y_on, true)
        } else if (v_bar_header & 0x8000) != 0 {
            // LONG VBAR_CACHE_HIT — replay the long entry as-is.
            let idx = v_bar_header & 0x7FFF;
            let entry = self.vbar_cache.get(&idx).cloned().unwrap_or_else(|| {
                // FreeRDP: "if cache was reset we need to fill in some dummy data".
                warn!("clearcodec: empty long vbar cache slot {idx}; using zero fill");
                VBarEntry {
                    pixels: vec![0u8; (v_bar_height as usize) * BPP],
                    count: v_bar_height,
                }
            });
            return Ok(entry.pixels);
        } else {
            return Err(ClearError::Malformed("invalid vbar header"));
        };

        // Build a fresh long-vbar entry: bg | short | bg, length = v_bar_height.
        let mut column = vec![0u8; (v_bar_height as usize) * BPP];
        let v_bar_pixel_count = v_bar_height;
        let short = short_pixels.unwrap();
        // Top: bg for `vbar_y_on` rows (clamped).
        let top = std::cmp::min(vbar_y_on, v_bar_pixel_count);
        for y in 0..top {
            let off = (y as usize) * BPP;
            column[off..off + BPP].copy_from_slice(bg);
        }
        // Middle: short pixels for `short.count` rows starting at vbar_y_on.
        let mid_start = top;
        let mid_count = std::cmp::min(short.count, v_bar_pixel_count.saturating_sub(mid_start));
        for y in 0..mid_count {
            let dst = ((mid_start + y) as usize) * BPP;
            let src = (y as usize) * BPP;
            if src + BPP > short.pixels.len() {
                break;
            }
            column[dst..dst + BPP].copy_from_slice(&short.pixels[src..src + BPP]);
        }
        // Bottom: bg for the rest.
        let bot_start = mid_start + mid_count;
        for y in bot_start..v_bar_pixel_count {
            let off = (y as usize) * BPP;
            column[off..off + BPP].copy_from_slice(bg);
        }

        if build_long {
            let entry = VBarEntry {
                pixels: column.clone(),
                count: v_bar_pixel_count,
            };
            let slot = self.vbar_cursor;
            self.vbar_cache.insert(slot, entry);
            self.vbar_cursor = (self.vbar_cursor + 1) % VBAR_CACHE_SIZE;
        }
        Ok(column)
    }
}

fn decode_residual(chunk: &[u8], width: u32, height: u32, tile: &mut [u8]) -> Result<()> {
    // RLE stream of (B,G,R, run) triples. Pixels stream out in row-major
    // order across the whole tile and must cover all width*height pixels —
    // FreeRDP enforces the same invariant.
    let mut c = Cursor::new(chunk);
    let pixel_count = (width as usize) * (height as usize);
    let mut idx: usize = 0;

    while !c.is_empty() {
        let b = c.u8()?;
        let g = c.u8()?;
        let r = c.u8()?;
        let mut run = u32::from(c.u8()?);
        if run >= 0xFF {
            run = u32::from(c.u16()?);
            if run >= 0xFFFF {
                run = c.u32()?;
            }
        }
        if (idx as u64).saturating_add(run as u64) > pixel_count as u64 {
            return Err(ClearError::Malformed("residual run exceeds tile"));
        }
        let rgba = [r, g, b, 0xFF];
        for _ in 0..run {
            let off = idx * BPP;
            tile[off..off + BPP].copy_from_slice(&rgba);
            idx += 1;
        }
    }
    if idx != pixel_count {
        return Err(ClearError::Malformed("residual short of tile"));
    }
    Ok(())
}

fn decode_subcodecs(mut chunk: &[u8], nwidth: u32, nheight: u32, tile: &mut [u8]) -> Result<()> {
    // Records: 13 bytes header + bitmapDataByteCount bytes of payload.
    while !chunk.is_empty() {
        let mut c = Cursor::new(chunk);
        let x_start = u32::from(c.u16()?);
        let y_start = u32::from(c.u16()?);
        let width = u32::from(c.u16()?);
        let height = u32::from(c.u16()?);
        let data_len = c.u32()? as usize;
        let subcodec_id = c.u8()?;
        if x_start.saturating_add(width) > nwidth || y_start.saturating_add(height) > nheight {
            return Err(ClearError::Malformed("subcodec rect exceeds tile"));
        }
        let payload = c.slice(data_len)?;
        match subcodec_id {
            0 => decode_subcodec_uncompressed(payload, x_start, y_start, width, height, nwidth, tile)?,
            1 => {
                // NSCodec — not implemented. Skip, leaving the region whatever
                // the residual/bands phase wrote.
                warn!(
                    "clearcodec: skipping NSCodec sub-region {}x{} at ({},{}) — not implemented",
                    width, height, x_start, y_start
                );
            }
            2 => decode_subcodec_rlex(payload, x_start, y_start, width, height, nwidth, tile)?,
            other => {
                return Err(ClearError::Malformed(match other {
                    _ => "unknown subcodec id",
                }));
            }
        }
        chunk = c.into_remaining();
    }
    Ok(())
}

fn decode_subcodec_uncompressed(
    payload: &[u8],
    x_start: u32,
    y_start: u32,
    width: u32,
    height: u32,
    nwidth: u32,
    tile: &mut [u8],
) -> Result<()> {
    // Tightly packed BGR24, row-major over the sub-region.
    let expected = (width as usize) * (height as usize) * 3;
    if payload.len() != expected {
        return Err(ClearError::Malformed("uncompressed subcodec size mismatch"));
    }
    for row in 0..height {
        let dst_y = y_start + row;
        let src_off = (row as usize) * (width as usize) * 3;
        for col in 0..width {
            let dst_x = x_start + col;
            let src = src_off + (col as usize) * 3;
            let dst = (dst_y as usize * nwidth as usize + dst_x as usize) * BPP;
            let b = payload[src];
            let g = payload[src + 1];
            let r = payload[src + 2];
            tile[dst..dst + BPP].copy_from_slice(&[r, g, b, 0xFF]);
        }
    }
    Ok(())
}

fn decode_subcodec_rlex(
    payload: &[u8],
    x_start: u32,
    y_start: u32,
    width: u32,
    height: u32,
    nwidth: u32,
    tile: &mut [u8],
) -> Result<()> {
    // Palette-and-runs over the sub-region, row-major. Tile-local (x, y)
    // walks the sub-region; we map onto the parent tile via (x_start, y_start).
    let mut c = Cursor::new(payload);
    let palette_count = c.u8()?;
    if palette_count == 0 || palette_count > 127 {
        return Err(ClearError::Malformed("rlex palette out of range"));
    }
    let mut palette = [[0u8; 4]; 128];
    for i in 0..usize::from(palette_count) {
        let b = c.u8()?;
        let g = c.u8()?;
        let r = c.u8()?;
        palette[i] = [r, g, b, 0xFF];
    }

    let pixel_count = (width as usize) * (height as usize);
    let num_bits = LOG2_FLOOR[(palette_count - 1) as usize] + 1;
    let stop_mask = MASK_8[num_bits as usize];
    let depth_mask = MASK_8[(8 - num_bits) as usize];

    let mut x: u32 = 0;
    let mut y: u32 = 0;
    let mut pixel_index: usize = 0;

    while !c.is_empty() {
        let tmp = c.u8()?;
        let mut run_length = u32::from(c.u8()?);
        let suite_depth = (tmp >> num_bits) & depth_mask;
        let stop_index = tmp & stop_mask;
        if stop_index < suite_depth {
            return Err(ClearError::Malformed("rlex start_index < 0"));
        }
        let start_index = stop_index - suite_depth;

        if run_length >= 0xFF {
            run_length = u32::from(c.u16()?);
            if run_length >= 0xFFFF {
                run_length = c.u32()?;
            }
        }
        if start_index >= palette_count || stop_index >= palette_count {
            return Err(ClearError::Malformed("rlex palette index out of range"));
        }

        // Run of the start-index colour.
        let run_color = palette[usize::from(start_index)];
        if pixel_index + run_length as usize > pixel_count {
            return Err(ClearError::Malformed("rlex run overflow"));
        }
        for _ in 0..run_length {
            write_rlex_pixel(tile, nwidth, x_start, y_start, x, y, width, &run_color);
            x += 1;
            if x >= width {
                x = 0;
                y += 1;
            }
        }
        pixel_index += run_length as usize;

        // Suite: one pixel per index from start_index..=stop_index.
        let suite_len = usize::from(suite_depth) + 1;
        if pixel_index + suite_len > pixel_count {
            return Err(ClearError::Malformed("rlex suite overflow"));
        }
        let mut suite_index = start_index;
        for _ in 0..suite_len {
            let color = palette[usize::from(suite_index)];
            write_rlex_pixel(tile, nwidth, x_start, y_start, x, y, width, &color);
            suite_index += 1;
            x += 1;
            if x >= width {
                x = 0;
                y += 1;
            }
        }
        pixel_index += suite_len;
    }

    if pixel_index != pixel_count {
        return Err(ClearError::Malformed("rlex short of tile"));
    }
    Ok(())
}

#[inline]
fn write_rlex_pixel(
    tile: &mut [u8],
    nwidth: u32,
    x_start: u32,
    y_start: u32,
    x: u32,
    y: u32,
    width: u32,
    color: &[u8; 4],
) {
    if x >= width {
        return;
    }
    let dst_x = x_start + x;
    let dst_y = y_start + y;
    let dst = (dst_y as usize * nwidth as usize + dst_x as usize) * BPP;
    if dst + BPP <= tile.len() {
        tile[dst..dst + BPP].copy_from_slice(color);
    }
}

/// Minimal cursor: reads little-endian primitives, returns slices, tracks
/// remaining. Errors are short-read only — bounds are checked everywhere
/// the wire format demands it.
struct Cursor<'a> {
    buf: &'a [u8],
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf }
    }
    fn remaining(&self) -> usize {
        self.buf.len()
    }
    fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }
    fn into_remaining(self) -> &'a [u8] {
        self.buf
    }
    fn need(&self, n: usize) -> Result<()> {
        if self.buf.len() < n {
            Err(ClearError::Short {
                need: n,
                have: self.buf.len(),
            })
        } else {
            Ok(())
        }
    }
    fn u8(&mut self) -> Result<u8> {
        self.need(1)?;
        let v = self.buf[0];
        self.buf = &self.buf[1..];
        Ok(v)
    }
    fn u16(&mut self) -> Result<u16> {
        self.need(2)?;
        let v = u16::from_le_bytes([self.buf[0], self.buf[1]]);
        self.buf = &self.buf[2..];
        Ok(v)
    }
    fn u32(&mut self) -> Result<u32> {
        self.need(4)?;
        let v = u32::from_le_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]);
        self.buf = &self.buf[4..];
        Ok(v)
    }
    fn slice(&mut self, n: usize) -> Result<&'a [u8]> {
        self.need(n)?;
        let (head, tail) = self.buf.split_at(n);
        self.buf = tail;
        Ok(head)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build(glyph_flags: u8, seq: u8, residual: &[u8], bands: &[u8], subcodecs: &[u8]) -> Vec<u8> {
        let mut v = vec![glyph_flags, seq];
        v.extend_from_slice(&(residual.len() as u32).to_le_bytes());
        v.extend_from_slice(&(bands.len() as u32).to_le_bytes());
        v.extend_from_slice(&(subcodecs.len() as u32).to_le_bytes());
        v.extend_from_slice(residual);
        v.extend_from_slice(bands);
        v.extend_from_slice(subcodecs);
        v
    }

    #[test]
    fn residual_solid_run_paints_full_tile() {
        // 4x2 tile, single run of 8 red pixels.
        let mut residual = Vec::new();
        residual.extend_from_slice(&[0x00, 0x00, 0xFF, 8]); // BGR red, run=8
        let payload = build(0, 0, &residual, &[], &[]);
        let mut dec = ClearDecoder::new();
        let tile = dec.decompress(&payload, 4, 2).unwrap();
        assert_eq!(tile.len(), 4 * 2 * 4);
        for px in tile.chunks_exact(4) {
            assert_eq!(px, &[0xFF, 0x00, 0x00, 0xFF]);
        }
    }

    #[test]
    fn residual_two_colours_extended_run() {
        // 4 red + 12 green, second run uses 0xFF escape into u16.
        let mut residual = Vec::new();
        residual.extend_from_slice(&[0x00, 0x00, 0xFF, 4]); // red, run=4
        residual.extend_from_slice(&[0x00, 0xFF, 0x00, 0xFF]); // green, escape
        residual.extend_from_slice(&12u16.to_le_bytes());
        let payload = build(0, 0, &residual, &[], &[]);
        let mut dec = ClearDecoder::new();
        let tile = dec.decompress(&payload, 4, 4).unwrap();
        for (i, px) in tile.chunks_exact(4).enumerate() {
            if i < 4 {
                assert_eq!(px, &[0xFF, 0, 0, 0xFF], "pixel {i} not red");
            } else {
                assert_eq!(px, &[0, 0xFF, 0, 0xFF], "pixel {i} not green");
            }
        }
    }

    #[test]
    fn glyph_index_then_hit_replays() {
        // First packet: GLYPH_INDEX=42, body paints solid blue 2x2.
        // Second packet: GLYPH_INDEX|GLYPH_HIT replays slot 42, body empty.
        let mut residual = Vec::new();
        residual.extend_from_slice(&[0xFF, 0x00, 0x00, 4]); // BGR blue, run=4
        let mut p1 = vec![FLAG_GLYPH_INDEX, 0];
        p1.extend_from_slice(&42u16.to_le_bytes());
        p1.extend_from_slice(&(residual.len() as u32).to_le_bytes());
        p1.extend_from_slice(&0u32.to_le_bytes()); // bands
        p1.extend_from_slice(&0u32.to_le_bytes()); // subcodecs
        p1.extend_from_slice(&residual);

        let mut dec = ClearDecoder::new();
        let tile1 = dec.decompress(&p1, 2, 2).unwrap();
        assert_eq!(&tile1[0..4], &[0, 0, 0xFF, 0xFF]);

        // Replay packet — no composition payload.
        let mut p2 = vec![FLAG_GLYPH_INDEX | FLAG_GLYPH_HIT, 1];
        p2.extend_from_slice(&42u16.to_le_bytes());
        let tile2 = dec.decompress(&p2, 2, 2).unwrap();
        assert_eq!(tile1, tile2);
    }

    #[test]
    fn subcodec_uncompressed_paints_region() {
        // 4x4 tile, 2x2 BGR24 sub-region at (1,1) painted red.
        let mut sub = Vec::new();
        sub.extend_from_slice(&1u16.to_le_bytes()); // x_start
        sub.extend_from_slice(&1u16.to_le_bytes()); // y_start
        sub.extend_from_slice(&2u16.to_le_bytes()); // width
        sub.extend_from_slice(&2u16.to_le_bytes()); // height
        let bgr_data: Vec<u8> = std::iter::repeat([0x00, 0x00, 0xFFu8])
            .take(4)
            .flatten()
            .collect();
        sub.extend_from_slice(&(bgr_data.len() as u32).to_le_bytes());
        sub.push(0); // subcodec_id = uncompressed
        sub.extend_from_slice(&bgr_data);

        // Pre-fill background with green via residual (4*4=16 pixels).
        let mut residual = Vec::new();
        residual.extend_from_slice(&[0x00, 0xFF, 0x00, 16]);
        let payload = build(0, 0, &residual, &[], &sub);
        let mut dec = ClearDecoder::new();
        let tile = dec.decompress(&payload, 4, 4).unwrap();

        // Inner 2x2 (rows 1-2, cols 1-2) is red; rest green.
        for y in 0..4 {
            for x in 0..4 {
                let off = (y * 4 + x) * 4;
                let expected = if (1..=2).contains(&x) && (1..=2).contains(&y) {
                    [0xFF, 0, 0, 0xFF]
                } else {
                    [0, 0xFF, 0, 0xFF]
                };
                assert_eq!(&tile[off..off + 4], &expected, "pixel ({x},{y})");
            }
        }
    }
}
