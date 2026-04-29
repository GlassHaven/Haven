// Offline ClearCodec parser. Reads <path> + <width>x<height>, dumps every
// residual / band header, reports where the parse diverges from the spec.
// Build with `cargo build --features host-cli --bin clear-trace`.
//
// Triage helper — intentionally lax about unused offsets, etc.

#![allow(unused_assignments, unused_variables)]

use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: clear-trace <path> <width> <height>");
        return ExitCode::from(2);
    }
    let path = &args[1];
    let width: u32 = args[2].parse().unwrap();
    let height: u32 = args[3].parse().unwrap();
    let buf = fs::read(path).unwrap();

    let mut off = 0usize;
    let glyph_flags = buf[off];
    off += 1;
    let seq = buf[off];
    off += 1;
    println!("glyph_flags=0x{glyph_flags:02x} seq={seq} tile={width}x{height} payload={}", buf.len());

    if (glyph_flags & 0x01) != 0 {
        let glyph = u16::from_le_bytes([buf[off], buf[off + 1]]);
        off += 2;
        println!("glyph_index={glyph}");
        if (glyph_flags & 0x02) != 0 {
            println!("=> GLYPH_HIT, no composition payload");
            return ExitCode::SUCCESS;
        }
    }

    if buf.len() - off < 12 {
        println!("no composition header");
        return ExitCode::SUCCESS;
    }
    let residual_bytes = u32::from_le_bytes([buf[off], buf[off+1], buf[off+2], buf[off+3]]) as usize; off += 4;
    let bands_bytes    = u32::from_le_bytes([buf[off], buf[off+1], buf[off+2], buf[off+3]]) as usize; off += 4;
    let subcodec_bytes = u32::from_le_bytes([buf[off], buf[off+1], buf[off+2], buf[off+3]]) as usize; off += 4;
    println!("composition: residual={residual_bytes} bands={bands_bytes} subcodec={subcodec_bytes}");
    println!("remaining after composition header: {}", buf.len() - off);

    let pixel_count = (width as usize) * (height as usize);
    if residual_bytes > 0 {
        println!("--- residual ({residual_bytes} bytes) ---");
        let mut i = 0usize;
        let mut painted: usize = 0;
        let mut record_count = 0usize;
        let chunk = &buf[off..off + residual_bytes];
        while i + 4 <= chunk.len() {
            let b = chunk[i]; let g = chunk[i+1]; let r = chunk[i+2]; i += 3;
            let mut run = chunk[i] as u32; i += 1;
            let mut bytes_used = 4u32;
            if run >= 0xFF {
                if i + 2 > chunk.len() { println!("truncated u16 escape at i={i}"); break; }
                run = u16::from_le_bytes([chunk[i], chunk[i+1]]) as u32; i += 2;
                bytes_used += 2;
                if run >= 0xFFFF {
                    if i + 4 > chunk.len() { println!("truncated u32 escape at i={i}"); break; }
                    run = u32::from_le_bytes([chunk[i], chunk[i+1], chunk[i+2], chunk[i+3]]); i += 4;
                    bytes_used += 4;
                }
            }
            painted += run as usize;
            record_count += 1;
            if record_count <= 8 || record_count % 200 == 0 {
                println!(
                    "  rec#{record_count} bgr=({b:02x},{g:02x},{r:02x}) run={run} (bytes={bytes_used}) painted_total={painted}/{pixel_count} bytes_consumed={i}/{}",
                    chunk.len()
                );
            }
        }
        println!(
            "residual end: {} records, painted={painted}/{pixel_count}, bytes_consumed={i}/{}",
            record_count, chunk.len()
        );
        off += residual_bytes;
    }
    if bands_bytes > 0 {
        println!("--- bands ({bands_bytes} bytes) ---");
        let chunk = &buf[off..off + bands_bytes];
        let mut i = 0usize;
        let mut band = 0;
        while i + 11 <= chunk.len() && band < 8 {
            let xs = u16::from_le_bytes([chunk[i], chunk[i+1]]); i += 2;
            let xe = u16::from_le_bytes([chunk[i], chunk[i+1]]); i += 2;
            let ys = u16::from_le_bytes([chunk[i], chunk[i+1]]); i += 2;
            let ye = u16::from_le_bytes([chunk[i], chunk[i+1]]); i += 2;
            let cb = chunk[i]; let cg = chunk[i+1]; let cr = chunk[i+2]; i += 3;
            println!(
                "  band#{band} x=[{xs}..{xe}] y=[{ys}..{ye}] bg=({cb:02x},{cg:02x},{cr:02x})  vbar_count={}",
                xe - xs + 1
            );
            band += 1;
            // bail before consuming vbars — just glance at headers
            return ExitCode::SUCCESS;
        }
        off += bands_bytes;
    }
    ExitCode::SUCCESS
}
