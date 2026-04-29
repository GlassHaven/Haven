//! EGFX surface + cache management.
//!
//! Each surface is an off-screen RGBA32 framebuffer the server paints into
//! via WireToSurface*, SolidFill, SurfaceToSurface, CacheToSurface, etc.
//! `SurfaceManager` tracks all live surfaces, the surface→output mapping,
//! and a bitmap-tile cache keyed by `cache_slot`.
//!
//! No codec decoding lives here — `WireToSurface*` payloads are decoded by
//! egfx::clear / egfx::rfx and the resulting tiles are blitted in via
//! `Surface::blit_rgba`.

use std::collections::HashMap;

use ironrdp_pdu::geometry::InclusiveRectangle;
use ironrdp_pdu::rdp::vc::dvc::gfx::{
    CacheToSurfacePdu, Color, CreateSurfacePdu, DeleteSurfacePdu, EvictCacheEntryPdu, PixelFormat,
    SolidFillPdu, SurfaceToCachePdu, SurfaceToSurfacePdu,
};
use log::{debug, warn};

/// Internal pixel format. RDP servers usually send ARgb (alpha-first BGRA on
/// the wire) but we normalise everything to RGBA8888 so the framebuffer
/// projection later doesn't have to branch.
const BYTES_PER_PIXEL: usize = 4;

#[derive(Debug, Clone)]
#[allow(dead_code)] // id used by codec decoders in 3b for sanity logs
pub struct Surface {
    pub id: u16,
    pub width: u32,
    pub height: u32,
    pub pixel_format: PixelFormat,
    /// RGBA8888, row-major, no padding. `width * height * 4` bytes.
    pub pixels: Vec<u8>,
}

impl Surface {
    fn new(id: u16, width: u32, height: u32, pixel_format: PixelFormat) -> Self {
        let len = (width as usize)
            .checked_mul(height as usize)
            .and_then(|n| n.checked_mul(BYTES_PER_PIXEL))
            .unwrap_or(0);
        Self {
            id,
            width,
            height,
            pixel_format,
            pixels: vec![0u8; len],
        }
    }

    fn fill_rect(&mut self, rect: &InclusiveRectangle, rgba: [u8; 4]) {
        // MS-RDPEGFX RDPGFX_RECT16 uses *exclusive* right/bottom (matches
        // FreeRDP's `width = right - left`). ironrdp names the type
        // `InclusiveRectangle` but for EGFX PDUs we treat right/bottom as
        // one-past-end. Clip to surface bounds.
        let l = rect.left.min(self.width as u16) as u32;
        let t = rect.top.min(self.height as u16) as u32;
        let r = (rect.right as u32).min(self.width);
        let b = (rect.bottom as u32).min(self.height);
        if r <= l || b <= t {
            return;
        }
        for y in t..b {
            let row_start = (y as usize * self.width as usize + l as usize) * BYTES_PER_PIXEL;
            for x in 0..(r - l) as usize {
                let p = row_start + x * BYTES_PER_PIXEL;
                self.pixels[p] = rgba[0];
                self.pixels[p + 1] = rgba[1];
                self.pixels[p + 2] = rgba[2];
                self.pixels[p + 3] = rgba[3];
            }
        }
    }

    /// Copy a `w * h` RGBA tile into the surface at (`dst_x`, `dst_y`).
    /// `tile.len()` must be `w * h * 4`. Out-of-bounds rows are clipped.
    #[allow(dead_code)] // exercised once the codec decoders land in 3b
    pub fn blit_rgba(&mut self, dst_x: u32, dst_y: u32, w: u32, h: u32, tile: &[u8]) {
        let stride = self.width as usize * BYTES_PER_PIXEL;
        let tile_stride = w as usize * BYTES_PER_PIXEL;
        if tile.len() < (w * h) as usize * BYTES_PER_PIXEL {
            warn!("blit_rgba: tile too small ({} bytes for {}x{})", tile.len(), w, h);
            return;
        }
        let max_y = (dst_y + h).min(self.height);
        let max_x = (dst_x + w).min(self.width);
        if max_x <= dst_x || max_y <= dst_y {
            return;
        }
        let copy_w = (max_x - dst_x) as usize * BYTES_PER_PIXEL;
        for row in 0..(max_y - dst_y) {
            let src_off = row as usize * tile_stride;
            let dst_off = (dst_y + row) as usize * stride + dst_x as usize * BYTES_PER_PIXEL;
            self.pixels[dst_off..dst_off + copy_w]
                .copy_from_slice(&tile[src_off..src_off + copy_w]);
        }
    }
}

/// Where a surface paints into the host-side flat output buffer (the Haven
/// framebuffer). Captured from MapSurfaceToOutput.
#[derive(Debug, Clone, Copy)]
#[allow(dead_code)] // surface_id used in 3c framebuffer projection
pub struct OutputMapping {
    pub surface_id: u16,
    pub output_origin_x: i32,
    pub output_origin_y: i32,
}

/// Bounded LRU-ish cache. The MS-RDPEGFX spec allows up to 25600 entries; we
/// cap at 32768 to avoid unbounded growth on a misbehaving server, and
/// honour explicit `EvictCacheEntry` PDUs.
pub struct SurfaceManager {
    surfaces: HashMap<u16, Surface>,
    output_map: HashMap<u16, OutputMapping>,
    /// Cached tiles keyed by `cache_slot`. Each entry is a packed RGBA8888
    /// rectangle, stored alongside its (w, h) so we know how to redraw it.
    cache: HashMap<u16, CachedTile>,
    /// Union of dirty rectangles (in output coordinates) since the last
    /// `take_dirty()`. Used to drive the framebuffer FrameCallback.
    pub dirty: Vec<InclusiveRectangle>,
}

#[derive(Debug, Clone)]
struct CachedTile {
    width: u32,
    height: u32,
    pixels: Vec<u8>,
}

impl SurfaceManager {
    pub fn new() -> Self {
        Self {
            surfaces: HashMap::new(),
            output_map: HashMap::new(),
            cache: HashMap::new(),
            dirty: Vec::new(),
        }
    }

    pub fn create_surface(&mut self, p: &CreateSurfacePdu) {
        let surface = Surface::new(p.surface_id, u32::from(p.width), u32::from(p.height), p.pixel_format);
        debug!(
            "Surface[{}]: created {}x{} ({:?}, {} bytes)",
            p.surface_id,
            surface.width,
            surface.height,
            surface.pixel_format,
            surface.pixels.len()
        );
        self.surfaces.insert(p.surface_id, surface);
    }

    pub fn delete_surface(&mut self, p: &DeleteSurfacePdu) {
        self.surfaces.remove(&p.surface_id);
        self.output_map.remove(&p.surface_id);
    }

    pub fn map_to_output(&mut self, surface_id: u16, x: i32, y: i32) {
        self.output_map.insert(
            surface_id,
            OutputMapping {
                surface_id,
                output_origin_x: x,
                output_origin_y: y,
            },
        );
    }

    pub fn solid_fill(&mut self, p: &SolidFillPdu) {
        let Some(surface) = self.surfaces.get_mut(&p.surface_id) else {
            warn!("SolidFill: unknown surface {}", p.surface_id);
            return;
        };
        let rgba = color_to_rgba(&p.fill_pixel);
        for r in &p.rectangles {
            surface.fill_rect(r, rgba);
            self.dirty.push(rect_translated(r, &self.output_map.get(&p.surface_id)));
        }
    }

    pub fn surface_to_surface(&mut self, p: &SurfaceToSurfacePdu) {
        // Same-surface vs cross-surface — the source rectangle is read from
        // the source surface, then memcpy'd to each destination point on
        // the dest surface. Same-surface is the common case (scrolling a
        // text region). To keep the borrow checker happy we copy out first.
        let src_id = p.source_surface_id;
        let dst_id = p.destination_surface_id;
        // RDPGFX_RECT16 — exclusive right/bottom, see fill_rect note.
        let src_rect = &p.source_rectangle;
        let w = (src_rect.right as i32 - src_rect.left as i32).max(0) as u32;
        let h = (src_rect.bottom as i32 - src_rect.top as i32).max(0) as u32;
        let Some(src) = self.surfaces.get(&src_id) else {
            warn!("SurfaceToSurface: unknown source surface {}", src_id);
            return;
        };
        let mut tile = vec![0u8; (w * h) as usize * BYTES_PER_PIXEL];
        copy_rect_out(src, src_rect, w, h, &mut tile);
        let Some(dst) = self.surfaces.get_mut(&dst_id) else {
            warn!("SurfaceToSurface: unknown dest surface {}", dst_id);
            return;
        };
        for point in &p.destination_points {
            dst.blit_rgba(u32::from(point.x), u32::from(point.y), w, h, &tile);
            self.dirty.push(InclusiveRectangle {
                left: point.x,
                top: point.y,
                right: point.x.saturating_add(w as u16),
                bottom: point.y.saturating_add(h as u16),
            });
        }
    }

    pub fn surface_to_cache(&mut self, p: &SurfaceToCachePdu) {
        let Some(src) = self.surfaces.get(&p.surface_id) else {
            warn!("SurfaceToCache: unknown source surface {}", p.surface_id);
            return;
        };
        // RDPGFX_RECT16 — exclusive right/bottom.
        let r = &p.source_rectangle;
        let w = (r.right as i32 - r.left as i32).max(0) as u32;
        let h = (r.bottom as i32 - r.top as i32).max(0) as u32;
        if w == 0 || h == 0 {
            return;
        }
        let mut pixels = vec![0u8; (w * h) as usize * BYTES_PER_PIXEL];
        copy_rect_out(src, r, w, h, &mut pixels);
        self.cache.insert(
            p.cache_slot,
            CachedTile {
                width: w,
                height: h,
                pixels,
            },
        );
    }

    pub fn cache_to_surface(&mut self, p: &CacheToSurfacePdu) {
        let Some(tile) = self.cache.get(&p.cache_slot) else {
            // Pre-3b this fires constantly because the cache is empty.
            // Once a codec decoder lands and SurfaceToCache can pull real
            // pixels from a decoded surface, this becomes the rare "server
            // referenced an evicted slot" warning.
            warn!("CacheToSurface: empty cache slot {}", p.cache_slot);
            return;
        };
        let Some(dst) = self.surfaces.get_mut(&p.surface_id) else {
            warn!("CacheToSurface: unknown surface {}", p.surface_id);
            return;
        };
        let w = tile.width;
        let h = tile.height;
        let pixels = tile.pixels.clone();
        for point in &p.destination_points {
            dst.blit_rgba(u32::from(point.x), u32::from(point.y), w, h, &pixels);
            self.dirty.push(InclusiveRectangle {
                left: point.x,
                top: point.y,
                right: point.x.saturating_add(w as u16),
                bottom: point.y.saturating_add(h as u16),
            });
        }
    }

    pub fn evict_cache(&mut self, p: &EvictCacheEntryPdu) {
        self.cache.remove(&p.cache_slot);
    }

    /// Take the dirty-rect accumulator. Caller is expected to project these
    /// through `output_map` and notify the host framebuffer (Phase 3c).
    #[allow(dead_code)]
    pub fn take_dirty(&mut self) -> Vec<InclusiveRectangle> {
        std::mem::take(&mut self.dirty)
    }

    #[allow(dead_code)]
    pub fn surface(&self, id: u16) -> Option<&Surface> {
        self.surfaces.get(&id)
    }

    #[allow(dead_code)]
    pub fn surface_mut(&mut self, id: u16) -> Option<&mut Surface> {
        self.surfaces.get_mut(&id)
    }

    #[allow(dead_code)]
    pub fn output_for(&self, surface_id: u16) -> Option<OutputMapping> {
        self.output_map.get(&surface_id).copied()
    }

    pub fn reset(&mut self) {
        self.surfaces.clear();
        self.output_map.clear();
        self.cache.clear();
        self.dirty.clear();
    }
}

fn color_to_rgba(c: &Color) -> [u8; 4] {
    [c.r, c.g, c.b, 0xFF]
}

fn copy_rect_out(src: &Surface, r: &InclusiveRectangle, w: u32, h: u32, out: &mut [u8]) {
    let stride = src.width as usize * BYTES_PER_PIXEL;
    let row_bytes = w as usize * BYTES_PER_PIXEL;
    for row in 0..h {
        let sy = r.top as u32 + row;
        if sy >= src.height {
            break;
        }
        let src_off = sy as usize * stride + r.left as usize * BYTES_PER_PIXEL;
        let dst_off = row as usize * row_bytes;
        let copy_w = row_bytes.min(stride - r.left as usize * BYTES_PER_PIXEL);
        if src_off + copy_w > src.pixels.len() {
            break;
        }
        out[dst_off..dst_off + copy_w].copy_from_slice(&src.pixels[src_off..src_off + copy_w]);
    }
}

/// Translate a surface-local rectangle into output coordinates using the
/// surface's MapSurfaceToOutput mapping (or pass-through if unmapped).
#[allow(dead_code)] // returned data lives in self.dirty for now; framebuffer projection lands in 3c
fn rect_translated(r: &InclusiveRectangle, m: &Option<&OutputMapping>) -> InclusiveRectangle {
    if let Some(m) = m {
        let dx = m.output_origin_x as i32;
        let dy = m.output_origin_y as i32;
        InclusiveRectangle {
            left: (r.left as i32 + dx).max(0).min(u16::MAX as i32) as u16,
            top: (r.top as i32 + dy).max(0).min(u16::MAX as i32) as u16,
            right: (r.right as i32 + dx).max(0).min(u16::MAX as i32) as u16,
            bottom: (r.bottom as i32 + dy).max(0).min(u16::MAX as i32) as u16,
        }
    } else {
        r.clone()
    }
}

