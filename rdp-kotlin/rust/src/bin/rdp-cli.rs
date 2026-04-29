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
    ClipboardCallback, FrameCallback, RdpClient, RdpConfig, SessionCallback,
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

    let config = RdpConfig {
        username: user,
        password: pass,
        domain,
        width: 1280,
        height: 800,
        color_depth: 32,
        enable_credssp: true,
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
    if let Err(e) = client.connect(host, port) {
        eprintln!("[connect-failed] {e:?}");
        return ExitCode::from(1);
    }

    let deadline = Instant::now() + Duration::from_secs(seconds);
    while Instant::now() < deadline {
        if error.load(Ordering::Acquire) {
            client.disconnect();
            return ExitCode::from(1);
        }
        std::thread::sleep(Duration::from_millis(200));
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
