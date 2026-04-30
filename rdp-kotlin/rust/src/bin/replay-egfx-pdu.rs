// Offline replay tool for files captured via EGFX_PDU_DUMP_DIR.
//
// Reads a single .bin file (or every .bin file in a directory) and decodes
// it as either a ServerPdu (the EGFX channel format) or a BitmapUpdateData
// (the legacy slow-path format). Prints a one-line summary per file plus,
// for the rectangle-bearing EGFX variants, both the inclusive and the
// exclusive interpretation of width/height — that's the "is this an
// IronRDP PR #1238 reproducer?" check.
//
// Build with `cargo build --features host-cli --bin replay-egfx-pdu`.
// Run as:
//
//   replay-egfx-pdu <path>                   # auto-detect from filename
//   replay-egfx-pdu --slow-path <path>       # force BitmapUpdateData
//   replay-egfx-pdu --egfx <path>            # force ServerPdu
//   replay-egfx-pdu /tmp/ws25-egfx           # batch mode: scan dir
//
// Exit non-zero if no file decodes — useful as a CI check on attached
// fixtures before sending them upstream.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use ironrdp_core::{Decode, ReadCursor};
use ironrdp_pdu::bitmap::BitmapUpdateData;
use ironrdp_pdu::rdp::vc::dvc::gfx::{
    Codec1Type, Codec2Type, ServerPdu, WireToSurface1Pdu, WireToSurface2Pdu,
};

#[derive(Copy, Clone, PartialEq, Eq)]
enum Mode {
    Auto,
    Egfx,
    SlowPath,
}

fn main() -> ExitCode {
    let mut mode = Mode::Auto;
    let mut paths: Vec<PathBuf> = Vec::new();
    for arg in std::env::args().skip(1) {
        match arg.as_str() {
            "--egfx" => mode = Mode::Egfx,
            "--slow-path" => mode = Mode::SlowPath,
            "-h" | "--help" => {
                print_help();
                return ExitCode::SUCCESS;
            }
            other if other.starts_with("--") => {
                eprintln!("unknown flag: {other}");
                print_help();
                return ExitCode::from(2);
            }
            other => paths.push(PathBuf::from(other)),
        }
    }
    if paths.is_empty() {
        print_help();
        return ExitCode::from(2);
    }

    let mut files: Vec<PathBuf> = Vec::new();
    for p in &paths {
        if p.is_dir() {
            let mut dir_entries: Vec<PathBuf> = match fs::read_dir(p) {
                Ok(rd) => rd
                    .filter_map(|e| e.ok())
                    .map(|e| e.path())
                    .filter(|f| f.extension().and_then(|s| s.to_str()) == Some("bin"))
                    .collect(),
                Err(e) => {
                    eprintln!("could not read dir {}: {e}", p.display());
                    return ExitCode::from(1);
                }
            };
            dir_entries.sort();
            files.extend(dir_entries);
        } else {
            files.push(p.clone());
        }
    }
    if files.is_empty() {
        eprintln!("no .bin files found in {paths:?}");
        return ExitCode::from(1);
    }

    let mut any_decoded = false;
    let mut any_failed = false;
    for path in &files {
        match decode_one(path, mode) {
            Ok(()) => any_decoded = true,
            Err(e) => {
                eprintln!("{}: {e}", path.display());
                any_failed = true;
            }
        }
    }
    if !any_decoded {
        ExitCode::from(1)
    } else if any_failed {
        ExitCode::from(2)
    } else {
        ExitCode::SUCCESS
    }
}

fn print_help() {
    eprintln!("usage: replay-egfx-pdu [--egfx|--slow-path] <path-or-dir> [...]");
    eprintln!();
    eprintln!("Decodes EGFX_PDU_DUMP_DIR captures and prints a summary.");
    eprintln!();
    eprintln!("  --egfx          force ServerPdu decode");
    eprintln!("  --slow-path     force legacy BitmapUpdateData decode");
    eprintln!("  (default)       auto-detect from filename:");
    eprintln!("                  slow_path_bitmap_update_*.bin → slow path,");
    eprintln!("                  everything else → EGFX ServerPdu.");
}

fn decode_one(path: &Path, mode: Mode) -> Result<(), String> {
    let bytes = fs::read(path).map_err(|e| format!("read failed: {e}"))?;
    let chosen = match mode {
        Mode::Auto => guess_mode(path),
        m => m,
    };
    match chosen {
        Mode::Egfx => decode_egfx(path, &bytes),
        Mode::SlowPath => decode_slow_path(path, &bytes),
        Mode::Auto => unreachable!(),
    }
}

fn guess_mode(path: &Path) -> Mode {
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
    if name.starts_with("slow_path_bitmap_update") {
        Mode::SlowPath
    } else {
        Mode::Egfx
    }
}

fn decode_egfx(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut cur = ReadCursor::new(bytes);
    let pdu = ServerPdu::decode(&mut cur)
        .map_err(|e| format!("ServerPdu::decode failed ({e}); {} bytes total", bytes.len()))?;
    let trailing = cur.len();
    let summary = summarise_server_pdu(&pdu);
    let pretty = path.file_name().and_then(|n| n.to_str()).unwrap_or("?");
    let trail_note = if trailing > 0 {
        format!(", {trailing} trailing — likely concatenated PDU")
    } else {
        String::new()
    };
    println!(
        "{pretty}: EGFX {summary} (decoded {} of {} bytes{trail_note})",
        bytes.len() - trailing,
        bytes.len(),
    );
    Ok(())
}

fn decode_slow_path(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut cur = ReadCursor::new(bytes);
    let upd = BitmapUpdateData::decode(&mut cur).map_err(|e| {
        format!(
            "BitmapUpdateData::decode failed ({e}); {} bytes total",
            bytes.len()
        )
    })?;
    let pretty = path.file_name().and_then(|n| n.to_str()).unwrap_or("?");
    println!(
        "{pretty}: slow-path BitmapUpdate, {} rectangles",
        upd.rectangles.len()
    );
    for (i, r) in upd.rectangles.iter().enumerate() {
        // The legacy slow-path TS_BITMAP_DATA carries rectangle (Inclusive)
        // plus explicit width/height fields, so the inclusive-vs-exclusive
        // ambiguity that PR #1238 addresses doesn't apply here — print
        // both for completeness.
        let rc = &r.rectangle;
        println!(
            "  [{i}] rect=({},{},{},{}) explicit={}x{} bpp={} compressed={:?} payload={}",
            rc.left,
            rc.top,
            rc.right,
            rc.bottom,
            r.width,
            r.height,
            r.bits_per_pixel,
            r.compression_flags,
            r.bitmap_data.len(),
        );
    }
    Ok(())
}

fn summarise_server_pdu(pdu: &ServerPdu) -> String {
    match pdu {
        ServerPdu::CapabilitiesConfirm(_) => "CapabilitiesConfirm".into(),
        ServerPdu::ResetGraphics(p) => format!(
            "ResetGraphics {}x{} monitors={}",
            p.width,
            p.height,
            p.monitors.len()
        ),
        ServerPdu::CreateSurface(p) => format!(
            "CreateSurface id={} {}x{} pf={:?}",
            p.surface_id, p.width, p.height, p.pixel_format
        ),
        ServerPdu::DeleteSurface(p) => format!("DeleteSurface id={}", p.surface_id),
        ServerPdu::MapSurfaceToOutput(p) => format!(
            "MapSurfaceToOutput id={} @({},{})",
            p.surface_id, p.output_origin_x, p.output_origin_y
        ),
        ServerPdu::MapSurfaceToScaledOutput(_) => "MapSurfaceToScaledOutput".into(),
        ServerPdu::MapSurfaceToScaledWindow(_) => "MapSurfaceToScaledWindow".into(),
        ServerPdu::StartFrame(p) => format!("StartFrame id={}", p.frame_id),
        ServerPdu::EndFrame(p) => format!("EndFrame id={}", p.frame_id),
        ServerPdu::WireToSurface1(p) => summarise_wts1(p),
        ServerPdu::WireToSurface2(p) => summarise_wts2(p),
        ServerPdu::SolidFill(p) => format!(
            "SolidFill surface={} pixel=#{:02x}{:02x}{:02x} rect_count={}",
            p.surface_id,
            p.fill_pixel.r,
            p.fill_pixel.g,
            p.fill_pixel.b,
            p.rectangles.len()
        ),
        ServerPdu::SurfaceToSurface(p) => format!(
            "SurfaceToSurface src={} dst={} dst_points={}",
            p.source_surface_id,
            p.destination_surface_id,
            p.destination_points.len()
        ),
        ServerPdu::SurfaceToCache(p) => format!(
            "SurfaceToCache surface={} cache_key=0x{:016x} cache_slot={}",
            p.surface_id, p.cache_key, p.cache_slot
        ),
        ServerPdu::CacheToSurface(p) => format!(
            "CacheToSurface cache_slot={} surface={} dst_points={}",
            p.cache_slot,
            p.surface_id,
            p.destination_points.len()
        ),
        ServerPdu::EvictCacheEntry(p) => format!("EvictCacheEntry cache_slot={}", p.cache_slot),
        ServerPdu::DeleteEncodingContext(p) => format!(
            "DeleteEncodingContext surface={} ctx={}",
            p.surface_id, p.codec_context_id
        ),
        ServerPdu::CacheImportReply(_) => "CacheImportReply".into(),
    }
}

fn summarise_wts1(p: &WireToSurface1Pdu) -> String {
    // RDPGFX_RECT16 destination_rectangle. Print both interpretations so
    // attached captures self-document what PR #1238 is actually fixing:
    // the wire bytes have right=W and bottom=H (exclusive, MS-RDPEGFX
    // 2.2.1.4.1), so the "exclusive" column is the spec answer. Today's
    // ironrdp parses the same bytes as InclusiveRectangle, so the
    // "inclusive" column is what trait method `Rectangle::width()`
    // currently returns. The two should match the explicit dimensions
    // a server sends — if they don't, the inclusive interpretation is
    // off-by-one.
    let r = &p.destination_rectangle;
    let w_inclusive = r.right.saturating_sub(r.left).saturating_add(1);
    let h_inclusive = r.bottom.saturating_sub(r.top).saturating_add(1);
    let w_exclusive = r.right.saturating_sub(r.left);
    let h_exclusive = r.bottom.saturating_sub(r.top);
    format!(
        "WireToSurface1 surface={} codec={} pf={:?} rect=({},{},{},{}) → {}x{} (inclusive) / {}x{} (exclusive)  payload={}",
        p.surface_id,
        codec1_label(p.codec_id),
        p.pixel_format,
        r.left,
        r.top,
        r.right,
        r.bottom,
        w_inclusive,
        h_inclusive,
        w_exclusive,
        h_exclusive,
        p.bitmap_data.len()
    )
}

fn summarise_wts2(p: &WireToSurface2Pdu) -> String {
    format!(
        "WireToSurface2 surface={} codec={} ctx={} pf={:?} payload={}",
        p.surface_id,
        codec2_label(p.codec_id),
        p.codec_context_id,
        p.pixel_format,
        p.bitmap_data.len()
    )
}

fn codec1_label(c: Codec1Type) -> &'static str {
    // Exhaustive: a new ironrdp variant should fail the build here so we
    // stay in sync with the upstream Codec1Type definition.
    match c {
        Codec1Type::Uncompressed => "Uncompressed",
        Codec1Type::RemoteFx => "RemoteFx",
        Codec1Type::ClearCodec => "ClearCodec",
        Codec1Type::Planar => "Planar",
        Codec1Type::Avc420 => "Avc420",
        Codec1Type::Alpha => "Alpha",
        Codec1Type::Avc444 => "Avc444",
        Codec1Type::Avc444v2 => "Avc444v2",
    }
}

fn codec2_label(c: Codec2Type) -> &'static str {
    match c {
        Codec2Type::RemoteFxProgressive => "RemoteFxProgressive",
    }
}
