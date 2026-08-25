use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex, RwLock};
use log::{debug, error, info, warn};

mod bitmap_bridge;
mod egfx;
mod redirection;
mod yuv;

uniffi::setup_scaffolding!();

/// Values that must never reach logcat (#477).
///
/// Haven's RDP logs exist to be attached to public issues, and ironrdp's own
/// `tracing` output dumps whole PDUs at debug level: `ConnectionRequest`
/// carries the logon name as the RDP Cookie, and `ClientInfoPdu` carries
/// `username` plus the server `address`, both in the clear. We cannot reformat
/// somebody else's `Debug` impl, and dropping those lines would cost the
/// connect-phase trace that #422 and #461 were both diagnosed from — so scrub
/// the formatted line instead. Exact-match replacement of values we already
/// hold cannot false-positive the way a pattern over arbitrary PDU text would,
/// and cannot miss a field we forgot to think about.
static SECRETS: RwLock<Vec<String>> = RwLock::new(Vec::new());

/// Add one value to the scrub list. Idempotent; safe to call per connect.
fn remember_secret(value: &str) {
    // Under 3 chars there is nothing worth hiding and a real risk of chewing
    // fragments out of unrelated text (an empty domain would match everywhere).
    if value.len() < 3 {
        return;
    }
    let mut secrets = SECRETS.write().unwrap_or_else(|e| e.into_inner());
    if !secrets.iter().any(|s| s == value) {
        secrets.push(value.to_owned());
    }
}

/// Every way a secret can appear in a log line, not just the way we were given it.
///
/// #477: the scrubber held "192.168.1.100" and the address still reached logcat,
/// because rustls does not print addresses as dotted quads — it prints the Rust
/// `Debug` of the octet array:
///
/// ```text
/// rustls::client::hs: No cached session for IpAddress(V4(Ipv4Addr([192, 168, 1, 100])))
/// ```
///
/// A substring search for the dotted form cannot match that, so an exact-match
/// scrubber is only as good as its guess about formatting — and third-party
/// crates format however they like. Where a secret parses as an IPv4 address,
/// its octet rendering is registered alongside it.
fn secret_renderings(secret: &str) -> Vec<String> {
    let mut forms = vec![secret.to_owned()];
    let octets: Vec<&str> = secret.split('.').collect();
    if octets.len() == 4 && octets.iter().all(|o| o.parse::<u8>().is_ok()) {
        forms.push(format!("[{}]", octets.join(", ")));
    }
    forms
}

fn scrub(line: &str, secrets: &[String]) -> String {
    let mut out = line.to_owned();
    for secret in secrets {
        for form in secret_renderings(secret) {
            // The `contains` guard keeps the common (nothing to redact) case down
            // to a substring search instead of a fresh allocation per secret.
            if out.contains(form.as_str()) {
                out = out.replace(form.as_str(), "<redacted>");
            }
        }
    }
    out
}

fn init_logging() {
    use std::sync::Once;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("RdpNative")
                // Reproduces android_logger's own `{module_path}: {args}`
                // layout for a custom tag — supplying a format replaces it.
                .format(|f, record| {
                    let line = format!(
                        "{}: {}",
                        record.module_path().unwrap_or_default(),
                        record.args()
                    );
                    let secrets = SECRETS.read().unwrap_or_else(|e| e.into_inner());
                    f.write_str(&scrub(&line, &secrets))
                }),
        );
    });
}

#[cfg(test)]
mod log_scrub_tests {
    use super::scrub;

    fn secrets() -> Vec<String> {
        ["skeezmo", "192.168.1.100", "desktop.lan"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    }

    /// Verbatim from the #477 logcat (v5.86.48), with the reporter's manual
    /// `--redacted--` put back to the account name he had to delete by hand.
    #[test]
    fn connection_request_cookie_is_scrubbed() {
        let line = concat!(
            "ironrdp_connector::connection: Send message=ConnectionRequest { ",
            "nego_data: Some(Cookie(Cookie(\"skeezmo\"))), flags: RequestFlags(0x0), ",
            "protocol: SecurityProtocol(SSL) }"
        );
        let out = scrub(line, &secrets());
        assert!(!out.contains("skeezmo"), "{out}");
        assert!(out.contains("Cookie(Cookie(\"<redacted>\"))"), "{out}");
    }

    #[test]
    fn client_info_username_and_address_are_scrubbed() {
        let line = concat!(
            "ironrdp_connector::connection: Send message=ClientInfoPdu { client_info: ",
            "ClientInfo { credentials: Credentials { username: \"skeezmo\", domain: None, .. }, ",
            "extra_info: ExtendedClientInfo { address_family: AddressFamily(2), ",
            "address: \"192.168.1.100\", dir: \"\" } } }"
        );
        let out = scrub(line, &secrets());
        assert!(!out.contains("skeezmo"), "{out}");
        assert!(!out.contains("192.168.1.100"), "{out}");
        // The PDU shape survives — this is still a usable connect-phase trace.
        assert!(out.contains("address_family: AddressFamily(2)"), "{out}");
    }

    /// #477: the exact line that reached a reporter's logcat while the scrubber
    /// already held this address. rustls prints the octet array, not the dotted
    /// quad, so the substring search never fired.
    #[test]
    fn an_address_printed_as_octets_is_scrubbed() {
        let line = "rustls::client::hs: No cached session for IpAddress(V4(Ipv4Addr([192, 168, 1, 100])))";
        let out = scrub(line, &secrets());
        assert!(!out.contains("192, 168, 1, 100"), "{out}");
        // Still a usable trace: the crate and the event survive.
        assert!(out.contains("rustls::client::hs"), "{out}");
    }

    /// A dotted quad that is not an address must not sprout a bogus octet form.
    #[test]
    fn a_non_address_secret_gains_no_octet_rendering() {
        let secrets = vec!["desktop.lan".to_string(), "1.2.3.999".to_string()];
        let line = "rdp_transport: connecting to host [1, 2, 3, 999]";
        assert_eq!(scrub(line, &secrets), line);
    }

    #[test]
    fn unrelated_lines_are_untouched() {
        let line = "rdp_transport: EGFX caps advertised: V8_1, V10_7";
        assert_eq!(scrub(line, &secrets()), line);
    }

    #[test]
    fn empty_secret_list_is_a_passthrough() {
        // A session that never connected registers nothing; logging must not
        // start mangling text just because the list is empty.
        let line = "ironrdp_connector: Send message=ConnectionRequest { .. }";
        assert_eq!(scrub(line, &[]), line);
    }
}

#[derive(Debug, uniffi::Error)]
pub enum RdpError {
    ConnectionFailed,
    AuthenticationFailed,
    ProtocolError,
    TlsError,
    Disconnected,
    IoError,
}

impl std::fmt::Display for RdpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RdpError::ConnectionFailed => write!(f, "Connection failed"),
            RdpError::AuthenticationFailed => write!(f, "Authentication failed"),
            RdpError::ProtocolError => write!(f, "Protocol error"),
            RdpError::TlsError => write!(f, "TLS error"),
            RdpError::Disconnected => write!(f, "Disconnected"),
            RdpError::IoError => write!(f, "I/O error"),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RdpConfig {
    pub username: String,
    pub password: String,
    pub domain: String,
    pub width: u16,
    pub height: u16,
    pub color_depth: u8,
    /// Request CredSSP / NLA during the handshake. Default true (callers
    /// should construct this with true). Set false to fall back to SSL-
    /// only security, useful against servers where ironrdp's CredSSP
    /// doesn't interop — #109, Windows Server 2025 Datacenter.
    pub enable_credssp: bool,
    /// Lowercase-hex SHA-256 of the server's DER leaf certificate that was
    /// pinned on a previous connection, or None on first connect. When set,
    /// the TLS handshake is aborted (before any credentials are sent) if the
    /// server presents a different certificate — trust-on-first-use, closing
    /// the "accepts any server certificate" MITM hole (security-review
    /// critical #2). The observed fingerprint is reported back via
    /// [SessionCallback::on_server_cert] so the caller can pin it.
    pub pinned_cert_sha256: Option<String>,
    /// #418: enable RemoteFX-Progressive WBT_TILE_UPGRADE refinement decoding.
    /// Hidden/debug opt-in — the upgrade path is not yet verified against real
    /// Windows captures, so callers default this to `false`.
    pub progressive_upgrade: bool,
    /// #425: advertise EGFX H.264/AVC420 support (V8.1 AVC420_ENABLED) so
    /// servers that only encode H.264 — notably KRDP — can drive the session.
    /// Requires an [`Avc420Decoder`] to be registered via `set_avc_decoder`;
    /// on Android that's a MediaCodec-backed decoder. The Android app sets this
    /// on by default (device-verified against KRDP) and always registers a
    /// decoder; callers that don't register one must pass false, else negotiated
    /// AVC tiles are dropped and the screen stays black.
    pub avc_enabled: bool,
    /// #504: the Windows keyboard-layout identifier (KLID, e.g. 0x0415 for
    /// Polish) announced in the GCC client core data. Servers that build the
    /// session's input layout from the announcement — Windows, xrdp, KRDP —
    /// would otherwise hand every non-US user a US layout. VirtualBox-style
    /// servers inject raw scancodes and ignore this entirely. Pass 0 to get
    /// the previous behaviour (0x0409, US English).
    pub keyboard_layout: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FrameData {
    pub width: u16,
    pub height: u16,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RdpRect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

/// Optional SOCKS5 endpoint used by [RdpClient::connect] in place of a
/// direct kernel dial. Lets IronRDP route its TCP through Haven's
/// in-app WireGuard / Tailscale tunnel via the per-tunnel localhost
/// SOCKS5 listener that wgbridge / tsbridge expose (#149 step 4).
#[derive(Debug, Clone, uniffi::Record)]
pub struct SocksProxyConfig {
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum MouseButton {
    Left,
    Right,
    Middle,
}

#[uniffi::export(with_foreign)]
pub trait FrameCallback: Send + Sync {
    fn on_frame_update(&self, x: u16, y: u16, w: u16, h: u16);
    fn on_resize(&self, width: u16, height: u16);
}

#[uniffi::export(with_foreign)]
pub trait ClipboardCallback: Send + Sync {
    fn on_remote_clipboard(&self, text: String);
}

/// #425: decodes one EGFX AVC420 (H.264 YUV420) access unit to RGBA. Rust owns
/// the framebuffer and decodes every other codec inline on the session thread,
/// so this is a **blocking** call: given the Annex-B bitstream for one frame's
/// region and the destination size, return `width*height*4` RGBA bytes (or an
/// empty Vec on failure, which the caller treats as "skip this tile").
///
/// The implementation MUST be **stateful across calls** — KRDP sends SPS+PPS+IDR
/// only in the first frame and P-slices (referencing the persistent decoded
/// picture) thereafter, so the same underlying decoder instance has to persist
/// and be fed access units in order. On Android this wraps a single MediaCodec
/// `video/avc` instance.
#[uniffi::export(with_foreign)]
pub trait Avc420Decoder: Send + Sync {
    /// Decode one access unit **into a buffer this side already owns**, and
    /// return how many bytes were written — 0 if the frame could not be
    /// produced.
    ///
    /// `dst_addr` is the address of `dst_len` writable bytes. The host wraps
    /// it as a direct byte buffer and writes **tightly-packed I420**:
    /// `width*height` luma, then two `((width+1)/2)*((height+1)/2)` chroma
    /// planes. It is expected to edge-replicate to `width`/`height` when its
    /// own output is smaller, so the written length is a pure function of the
    /// arguments and a short write is a bug rather than a crop.
    ///
    /// **Why not return a `Vec<u8>`, which is what this used to do.** That
    /// shape cost 47 ms per 1080p frame in a decoder that did no decoding at
    /// all — measured by `benchmark_avc_boundary`, linear in payload at about
    /// 62 MB/s, against 45 GB/s for the same copy inside Rust. Writing into
    /// place instead measured 0.24 ms for the same 3037 KB, a 194x
    /// difference, and it is the whole of the ~80 ms per frame that neither
    /// side could account for in #466.
    ///
    /// # Safety contract
    ///
    /// The host must write at most `dst_len` bytes and must not retain
    /// `dst_addr` past the call. This side guarantees the buffer stays alive
    /// and unmoved for the duration of the call, and never publishes the
    /// address anywhere else. The returned length is validated against the
    /// expected frame size before any of it is read.
    fn decode_into(
        &self,
        annex_b: Vec<u8>,
        width: u16,
        height: u16,
        dst_addr: u64,
        dst_len: u64,
    ) -> u32;
}

/// Timings from [`benchmark_avc_boundary`], in microseconds, totalled over the
/// requested iteration count.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AvcBoundaryTiming {
    /// Iterations actually run.
    pub iterations: u32,
    /// Total time inside `decode_to_i420`, measured exactly where the EGFX
    /// path measures its `avc round trip`.
    pub call_us: u64,
    /// Total time the host reported spending in its own body, summed from the
    /// value the stand-in decoder encodes in the first 8 bytes it returns.
    /// Zero when the stand-in does not report one.
    pub host_us: u64,
    /// Control: the same number of Rust-side allocate-and-copy operations of
    /// the same buffer size, with no boundary crossing at all. This is the
    /// yardstick the reporter's field logs carry, run under the same clock.
    pub memcpy_us: u64,
    /// Bytes each iteration carried back across the boundary.
    pub payload_bytes: u64,
}

/// Drive the [`Avc420Decoder`] boundary with no decoding, to separate the cost
/// of *crossing* from the cost of the work on the far side (#466).
///
/// This is how the write-into-place shape was chosen. The previous shape —
/// the host returning a `Vec<u8>` — measured 47 ms per 1080p frame here with
/// a stand-in decoder that did nothing, linear in payload at ~62 MB/s. This
/// one measured 0.24 ms for the same 3037 KB. Keep it: it is the regression
/// guard for the boundary that fix rests on, and it needs no RDP server, no
/// H.264 and no device. See `rdp-kotlin/bench/`.
///
/// Pass a stand-in decoder that does as little as possible. Whatever
/// `call_us` exceeds `host_us + memcpy_us` by is the crossing itself.
#[uniffi::export]
pub fn benchmark_avc_boundary(
    decoder: Arc<dyn Avc420Decoder>,
    width: u16,
    height: u16,
    iterations: u32,
) -> AvcBoundaryTiming {
    // A plausible compressed access unit. Size only matters for the argument
    // direction, which is small next to the returned frame either way.
    let annex_b = vec![0u8; 8 * 1024];
    let expected = crate::yuv::i420_len(width as usize, height as usize).unwrap_or(0);
    let mut dst = vec![0u8; expected];
    let addr = dst.as_mut_ptr() as u64;
    let len = dst.len() as u64;

    // Warm the far side up before timing: the first call through a foreign
    // trait pays one-off costs (JIT, class init, thread attach) that would
    // otherwise be smeared across a short run and read as per-frame cost.
    let _ = decoder.decode_into(annex_b.clone(), width, height, addr, len);

    let mut call_us = 0u64;
    let mut host_us = 0u64;
    let mut written = 0u64;
    for _ in 0..iterations {
        let t = std::time::Instant::now();
        let n = decoder.decode_into(annex_b.clone(), width, height, addr, len);
        call_us += t.elapsed().as_micros() as u64;
        written = n as u64;
        if dst.len() >= 8 {
            host_us += u64::from_le_bytes(dst[..8].try_into().unwrap_or([0; 8]));
        }
    }

    // The control, run identically: allocate a buffer of the same size and
    // copy into it, in Rust, no boundary involved.
    let src = vec![0u8; expected];
    let mut memcpy_us = 0u64;
    for _ in 0..iterations {
        let t = std::time::Instant::now();
        let mut probe = vec![0u8; src.len()];
        probe.copy_from_slice(&src);
        std::hint::black_box(&probe);
        memcpy_us += t.elapsed().as_micros() as u64;
    }

    AvcBoundaryTiming { iterations, call_us, host_us, memcpy_us, payload_bytes: written }
}

/// Server-side pointer (cursor) updates. RDP servers send the cursor shape and
/// position out-of-band rather than baking it into the framebuffer, so without
/// this the RDP viewer has no cursor at all (unlike VNC) — see #212. We enable
/// `enable_server_pointer` and forward the decoded shape to Kotlin, which draws
/// it as an overlay at the tracked pointer position. `rgba` is the decoded
/// pointer bitmap (RGBA, non-premultiplied alpha — `pointer_software_rendering`
/// stays off so we composite client-side).
#[uniffi::export(with_foreign)]
pub trait PointerCallback: Send + Sync {
    fn on_pointer_bitmap(
        &self,
        width: u16,
        height: u16,
        hotspot_x: u16,
        hotspot_y: u16,
        rgba: Vec<u8>,
    );
    /// Server requested the pointer be hidden (e.g. video playback, games).
    fn on_pointer_hidden(&self);
    /// Server requested the default/system pointer (keep the last shape).
    fn on_pointer_default(&self);
    /// Server moved the pointer (used in DIRECT mode; TOUCHPAD uses the
    /// client-tracked virtual cursor).
    fn on_pointer_position(&self, x: u16, y: u16);
}

/// Lifecycle + error surface for the RDP session, driven from the session
/// thread. Kotlin uses this to decide when to show the frame vs. a connecting
/// state vs. an error. Previously every failure in `run_rdp_session` went to
/// the Rust `log` crate (visible only via `adb logcat`), so the UI sat on the
/// empty placeholder with no explanation.
#[uniffi::export(with_foreign)]
pub trait SessionCallback: Send + Sync {
    /// Fired once the RDP handshake + capability exchange completes and the
    /// server has reported a desktop size. `on_resize` fires immediately
    /// after; frames follow.
    fn on_connected(&self, width: u16, height: u16);
    /// Fired when the session thread terminates with an error. `message` is
    /// an English description of the last failure observed.
    fn on_error(&self, message: String);
    /// Fired when the session thread exits cleanly (graceful server
    /// disconnect or local `disconnect()`).
    fn on_disconnected(&self);
    /// Fired right after the TLS handshake with the lowercase-hex SHA-256 of
    /// the server's DER leaf certificate. The caller pins this on first use
    /// (trust-on-first-use); a later change is rejected during the handshake
    /// via `RdpConfig.pinned_cert_sha256` before any credentials are sent.
    fn on_server_cert(&self, sha256: String);
}

/// Internal state for the RDP session.
struct SessionState {
    connected: bool,
    framebuffer: Option<FrameData>,
    dirty_rects: Vec<RdpRect>,
    frame_callback: Option<Arc<dyn FrameCallback>>,
    clipboard_callback: Option<Arc<dyn ClipboardCallback>>,
    session_callback: Option<Arc<dyn SessionCallback>>,
    pointer_callback: Option<Arc<dyn PointerCallback>>,
    /// #425: MediaCodec-backed H.264 decoder for EGFX AVC420 tiles (KRDP).
    /// None unless the host registered one via `set_avc_decoder`.
    avc_decoder: Option<Arc<dyn Avc420Decoder>>,
    shutdown: bool,
    /// EGFX per-frame timing summaries, drained by the host into the in-app
    /// verbose log (#477).
    ///
    /// These already went to the Android log, which needs adb to read — so the
    /// one measurement that discriminates "decode is slow" from "frames are
    /// arriving late" was invisible to the people reporting that it is slow.
    /// A reporter on #477 spent three rounds describing symptoms nobody could
    /// attribute because of it.
    perf_log: Vec<String>,
}

/// Perf lines kept before the oldest is dropped. A reporter needs the recent
/// steady state, not a session's whole history, and this is held in memory for
/// the life of the session.
const MAX_PERF_LOG_LINES: usize = 64;

impl SessionState {
    /// Record a perf line for the host to drain, discarding the oldest once
    /// full so a long session cannot grow this without bound.
    fn push_perf(&mut self, line: String) {
        if self.perf_log.len() >= MAX_PERF_LOG_LINES {
            self.perf_log.remove(0);
        }
        self.perf_log.push(line);
    }
}

/// Input events queued by the Kotlin side, consumed by the session thread.
enum InputEvent {
    Key { scancode: u16, pressed: bool },
    UnicodeKey { ch: u32, pressed: bool },
    MouseMove { x: u16, y: u16 },
    MouseButton { button: MouseButton, pressed: bool },
    MouseWheel { vertical: bool, delta: i16 },
    ClipboardText(String),
}

#[derive(uniffi::Object)]
pub struct RdpClient {
    config: RdpConfig,
    state: Arc<RwLock<SessionState>>,
    /// Key the JNI bitmap bridge uses to find `state` (#466). A raw JNI entry
    /// point cannot reach a UniFFI object, so the state is registered here and
    /// Kotlin passes this id back in.
    bitmap_bridge_id: i64,
    input_queue: Arc<Mutex<Vec<InputEvent>>>,
    session_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl Drop for RdpClient {
    fn drop(&mut self) {
        // Weak refs mean a stale entry is harmless, but a long-lived process
        // should not accumulate them.
        bitmap_bridge::unregister(self.bitmap_bridge_id);
    }
}

#[uniffi::export]
impl RdpClient {
    #[uniffi::constructor]
    pub fn new(config: RdpConfig) -> Self {
        init_logging();
        let state = Arc::new(RwLock::new(SessionState {
                connected: false,
                framebuffer: None,
                dirty_rects: Vec::new(),
                frame_callback: None,
                clipboard_callback: None,
                session_callback: None,
                pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }));
        let bitmap_bridge_id = bitmap_bridge::register(&state);
        Self {
            config,
            state,
            bitmap_bridge_id,
            input_queue: Arc::new(Mutex::new(Vec::new())),
            session_thread: Mutex::new(None),
        }
    }

    /// Key for the JNI bitmap bridge (#466). Kotlin passes this to
    /// `RdpBitmapBridge.blitRegion` so a raw JNI call can find this session's
    /// framebuffer; UniFFI objects are opaque handles and cannot be reached
    /// from hand-written JNI any other way.
    pub fn bitmap_bridge_id(&self) -> i64 {
        self.bitmap_bridge_id
    }

    pub fn connect(
        &self,
        host: String,
        port: u16,
        socks_proxy: Option<SocksProxyConfig>,
    ) -> Result<(), RdpError> {
        // Before the first PDU is logged (#477). The Kotlin side already logs
        // the target's shape, so nothing here needs to name it.
        remember_secret(&self.config.username);
        remember_secret(&self.config.domain);
        remember_secret(&self.config.password);
        remember_secret(&host);
        if let Some(ref proxy) = socks_proxy {
            remember_secret(&proxy.host);
        }

        let stream = match socks_proxy {
            Some(ref proxy) => socks5_connect(&proxy.host, proxy.port, &host, port).map_err(|e| {
                error!("SOCKS5 connect failed: {}", e);
                RdpError::ConnectionFailed
            })?,
            None => {
                let addr = format!("{}:{}", host, port);
                TcpStream::connect(&addr).map_err(|e| {
                    // TCP-level failure surfaces synchronously — no thread yet,
                    // no callback to fire.
                    error!("TCP connect failed: {}", e);
                    RdpError::ConnectionFailed
                })?
            }
        };
        stream
            .set_nonblocking(false)
            .map_err(|_| RdpError::IoError)?;
        // Generous read timeout for the handshake phase. CredSSP/NLA
        // against Windows servers can take >1s to round-trip the NTLM
        // challenge; 100ms was too short and surfaced as WouldBlock
        // mid-handshake (#109 — surf5726's Windows RDP target). The
        // session loop shrinks this back to 100ms after connect_finalize
        // so shutdown polling stays responsive.
        stream
            .set_read_timeout(Some(std::time::Duration::from_secs(30)))
            .map_err(|_| RdpError::IoError)?;

        let server_addr: SocketAddr = stream.peer_addr().map_err(|_| RdpError::IoError)?;
        // ClientInfoPdu's ExtendedClientInfo prints this one as a bare IP, so
        // the hostname registered above would not have caught it (#477).
        remember_secret(&server_addr.ip().to_string());

        let config = self.config.clone();
        let state = Arc::clone(&self.state);
        let input_queue = Arc::clone(&self.input_queue);
        let server_name = host.clone();
        // #117: a followed redirection reconnects from inside the session
        // thread, through the same route (SOCKS or direct) as the original.
        let redial_host = host.clone();
        let redial_socks = socks_proxy.clone();

        let handle = std::thread::Builder::new()
            .name("rdp-session".into())
            .spawn(move || {
                // #422: a panic in the decode path used to abort the process —
                // the reporter's log ends in a native tombstone in
                // librdp_transport.so with no Java frames, after 1550 24-bpp RLE
                // bitmaps. Catch it here so a bad frame kills this session and
                // reports itself, instead of taking Haven with it. Paired with
                // panic = "unwind" in the release profile; under abort this
                // never runs.
                //
                // AssertUnwindSafe is honest rather than convenient: past a
                // panic we touch `state` only to mark the session dead.
                // #117: a server redirection ends the attempt with a replay
                // plan; reconnect to the same endpoint and run the session
                // again with the plan applied. Exactly one hop — chained
                // redirects fail out of the loop.
                let mut redirect_plan: Option<redirection::RedirectFollow> = None;
                let mut stream_slot = Some(stream);
                let result = loop {
                    let attempt_stream = match stream_slot.take() {
                        Some(s) => s,
                        None => match redial(&redial_host, port, redial_socks.as_ref()) {
                            Ok(s) => s,
                            Err(e) => break Err(format!("redirect reconnect failed: {e}")),
                        },
                    };
                    let attempt = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        run_rdp_session(
                            attempt_stream,
                            &config,
                            &state,
                            &input_queue,
                            &server_name,
                            server_addr,
                            redirect_plan.as_ref(),
                        )
                    }))
                    .unwrap_or_else(|payload| {
                        let what = payload
                            .downcast_ref::<&'static str>()
                            .map(|s| (*s).to_owned())
                            .or_else(|| payload.downcast_ref::<String>().cloned())
                            .unwrap_or_else(|| "unknown panic".to_owned());
                        error!("RDP session panicked: {what}");
                        Err(format!(
                            "internal error decoding the RDP stream: {what} \
                             — the session was dropped to keep the app running"
                        ))
                    });
                    match attempt {
                        Ok(Some(plan)) => {
                            redirect_plan = Some(plan);
                            continue;
                        }
                        Ok(None) => break Ok(()),
                        Err(e) => break Err(e),
                    }
                };
                let session_cb = state.read().ok().and_then(|s| s.session_callback.clone());
                match result {
                    Err(e) => {
                        error!("RDP session error: {}", e);
                        if let Some(cb) = session_cb {
                            cb.on_error(format!("{}", e));
                        }
                    }
                    Ok(()) => {
                        info!("RDP session exited cleanly");
                        if let Some(cb) = session_cb {
                            cb.on_disconnected();
                        }
                    }
                }
                if let Ok(mut s) = state.write() {
                    s.connected = false;
                }
            })
            .map_err(|_| RdpError::IoError)?;

        if let Ok(mut s) = self.state.write() {
            s.shutdown = false;
        }
        if let Ok(mut jh) = self.session_thread.lock() {
            *jh = Some(handle);
        }

        Ok(())
    }

    pub fn disconnect(&self) {
        if let Ok(mut s) = self.state.write() {
            s.shutdown = true;
            s.connected = false;
        }
        if let Ok(mut jh) = self.session_thread.lock() {
            if let Some(handle) = jh.take() {
                let _ = handle.join();
            }
        }
    }

    pub fn is_connected(&self) -> bool {
        self.state.read().map(|s| s.connected).unwrap_or(false)
    }

    pub fn get_framebuffer(&self) -> Option<FrameData> {
        self.state.read().ok()?.framebuffer.clone()
    }

    /// Tightly-packed RGBA for just `(x, y, w, h)` of the framebuffer.
    ///
    /// #422: the host repaints only the region the server changed, but had to
    /// fetch the WHOLE framebuffer to get at it — on a 1920x1080 session that
    /// is an 8.29 MB copy per update, for a median update of about 41 KB.
    /// The rect is clipped to the framebuffer; `None` means there is nothing
    /// to copy (no framebuffer, or an empty/out-of-bounds rect) and the caller
    /// should fall back to a full repaint.
    pub fn get_framebuffer_region(&self, x: u16, y: u16, w: u16, h: u16) -> Option<FrameData> {
        let state = self.state.read().ok()?;
        let fb = state.framebuffer.as_ref()?;
        let (fb_w, fb_h) = (fb.width as usize, fb.height as usize);
        let (x, y) = (x as usize, y as usize);
        if x >= fb_w || y >= fb_h {
            return None;
        }
        let w = (w as usize).min(fb_w - x);
        let h = (h as usize).min(fb_h - y);
        if w == 0 || h == 0 {
            return None;
        }
        let stride = fb_w * 4;
        let row_bytes = w * 4;
        let mut pixels = Vec::with_capacity(row_bytes * h);
        for row in 0..h {
            let start = (y + row) * stride + x * 4;
            pixels.extend_from_slice(&fb.pixels[start..start + row_bytes]);
        }
        Some(FrameData {
            width: w as u16,
            height: h as u16,
            pixels,
        })
    }

    pub fn get_dirty_rects(&self) -> Vec<RdpRect> {
        if let Ok(mut s) = self.state.write() {
            std::mem::take(&mut s.dirty_rects)
        } else {
            Vec::new()
        }
    }

    /// Drain the EGFX per-frame timing summaries recorded since the last call
    /// (#477), so the host can put them in the verbose log a reporter can copy.
    ///
    /// Draining rather than reading: the host appends these to a log it already
    /// keeps, and returning them twice would duplicate lines in it.
    pub fn take_perf_log(&self) -> Vec<String> {
        if let Ok(mut s) = self.state.write() {
            std::mem::take(&mut s.perf_log)
        } else {
            Vec::new()
        }
    }

    pub fn set_frame_callback(&self, cb: Arc<dyn FrameCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.frame_callback = Some(cb);
        }
    }

    pub fn set_session_callback(&self, cb: Arc<dyn SessionCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.session_callback = Some(cb);
        }
    }

    pub fn send_key(&self, scancode: u16, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::Key { scancode, pressed });
        }
    }

    pub fn send_unicode_key(&self, ch: u32, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::UnicodeKey { ch, pressed });
        }
    }

    pub fn send_mouse_move(&self, x: u16, y: u16) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseMove { x, y });
        }
    }

    pub fn send_mouse_button(&self, button: MouseButton, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseButton { button, pressed });
        }
    }

    pub fn send_mouse_wheel(&self, vertical: bool, delta: i16) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseWheel { vertical, delta });
        }
    }

    pub fn send_clipboard_text(&self, text: String) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::ClipboardText(text));
        }
    }

    pub fn set_clipboard_callback(&self, cb: Arc<dyn ClipboardCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.clipboard_callback = Some(cb);
        }
    }

    /// #425: register the AVC420 (H.264) decoder used for EGFX tiles from
    /// H.264-only servers (KRDP). Must be set before `connect` when
    /// `RdpConfig.avc_enabled` is true, else negotiated AVC tiles are dropped.
    pub fn set_avc_decoder(&self, cb: Arc<dyn Avc420Decoder>) {
        if let Ok(mut s) = self.state.write() {
            s.avc_decoder = Some(cb);
        }
    }

    pub fn set_pointer_callback(&self, cb: Arc<dyn PointerCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.pointer_callback = Some(cb);
        }
    }
}

/// Build the ironrdp Config with all required fields.
/// Re-dials the RDP endpoint for a followed redirection (#117), through the
/// same route as the original connection, with the same socket setup the
/// handshake needs (blocking, generous read timeout — the session loop
/// shrinks it after finalize).
fn redial(host: &str, port: u16, socks: Option<&SocksProxyConfig>) -> Result<TcpStream, String> {
    let stream = match socks {
        Some(proxy) => socks5_connect(&proxy.host, proxy.port, host, port)
            .map_err(|e| format!("SOCKS5 reconnect failed: {e}"))?,
        None => TcpStream::connect((host, port)).map_err(|e| format!("TCP reconnect failed: {e}"))?,
    };
    stream
        .set_nonblocking(false)
        .map_err(|e| format!("reconnect socket setup failed: {e}"))?;
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(30)))
        .map_err(|e| format!("reconnect socket setup failed: {e}"))?;
    Ok(stream)
}

fn build_config(config: &RdpConfig) -> ironrdp_connector::Config {
    use ironrdp_connector::*;
    use ironrdp_pdu::gcc;

    Config {
        credentials: Credentials::UsernamePassword {
            username: config.username.clone().into(),
            password: config.password.clone().into(),
        },
        // New in connector 0.9; keep prior behaviour (none requested).
        alternate_shell: String::new(),
        work_dir: String::new(),
        compression_type: None,
        multitransport_flags: None,
        domain: if config.domain.is_empty() {
            None
        } else {
            Some(config.domain.clone())
        },
        enable_tls: true,
        enable_credssp: config.enable_credssp,
        desktop_size: DesktopSize {
            width: config.width,
            height: config.height,
        },
        desktop_scale_factor: 0,
        client_build: 0,
        client_name: "Haven".to_string(),
        keyboard_type: gcc::KeyboardType::IbmEnhanced,
        keyboard_subtype: 0,
        keyboard_functional_keys_count: 12,
        keyboard_layout: if config.keyboard_layout == 0 {
            0x0409 // US English — the pre-#504 hardcoded announcement
        } else {
            config.keyboard_layout
        },
        // Advertised network profile. Haven is a mobile client and the link is
        // usually WAN or worse, but this only tunes the server's own
        // heuristics — it does not gate any feature we depend on.
        connection_type: gcc::ConnectionType::Lan,
        // The whole reason the vendored connector fork existed: without this
        // bit, modern Windows servers never open the EGFX dynamic virtual
        // channel and Haven's ClearCodec/RemoteFX-Progressive decoders are
        // never fed (#418, #425). The fork OR'd it in unconditionally; upstream
        // takes it as an opt-in Config flag (Devolutions/IronRDP#1237), and
        // Haven has the EGFX DVC processor wired, so it opts in.
        support_dyn_vc_gfx_protocol: true,
        ime_file_name: String::new(),
        bitmap: Some(BitmapConfig {
            lossy_compression: true,
            // Honour the depth Kotlin passes. Kotlin defaults to 32 in
            // RdpSession, which Windows Server negotiates as 32bpp +
            // RemoteFX — smooth tile-based updates. Previously
            // hardcoded to 16 here for xrdp compatibility (xrdp's
            // 32bpp uses a custom RLE variant that ironrdp doesn't
            // decode), but that meant Windows users were stuck on
            // 16bpp interleaved RLE — line-by-line repaints, surf5726
            // on #109. xrdp users who need lower depth can be served
            // by a per-profile picker (follow-up to v5.24.40).
            color_depth: u32::from(config.color_depth),
            codecs: {
                use ironrdp_pdu::rdp::capability_sets::*;
                BitmapCodecs(vec![
                    Codec {
                        id: 0, // assigned by encoder from GUID
                        property: CodecProperty::RemoteFx(
                            RemoteFxContainer::ClientContainer(RfxClientCapsContainer {
                                capture_flags: CaptureFlags::empty(),
                                caps_data: RfxCaps(RfxCapset(vec![RfxICap {
                                    flags: RfxICapFlags::CODEC_MODE,
                                    entropy_bits: EntropyBits::Rlgr3,
                                }])),
                            }),
                        ),
                    },
                    Codec {
                        id: 0,
                        property: CodecProperty::ImageRemoteFx(
                            RemoteFxContainer::ClientContainer(RfxClientCapsContainer {
                                capture_flags: CaptureFlags::empty(),
                                caps_data: RfxCaps(RfxCapset(vec![RfxICap {
                                    flags: RfxICapFlags::CODEC_MODE,
                                    entropy_bits: EntropyBits::Rlgr3,
                                }])),
                            }),
                        ),
                    },
                    // NSCodec is deliberately NOT advertised (#461).
                    //
                    // Advertising it lets a Windows server assign it a codec id
                    // (1 in practice) and send fast-path SET_SURFACE_BITS with
                    // it — but that route is decoded by ironrdp-session, whose
                    // CodecId::from_u8 accepts only NONE(0), REMOTEFX(3) and
                    // QOI(0x0A/0x0B). An id it does not know is a hard error,
                    // not a skipped region, so the whole session dies with
                    // `Fast-Path: unexpected codec ID: 1` mid-logon.
                    //
                    // Haven's NSCodec support is the sub-region decoder INSIDE
                    // ClearCodec on the EGFX channel (#418) — a different
                    // container entirely, and no help here. Advertising a codec
                    // this path cannot decode only invites the server to use it.
                    // Windows falls back to RemoteFX or uncompressed bitmaps,
                    // both of which are decodable.
                ])
            },
        }),
        dig_product_id: String::new(),
        client_dir: String::new(),
        platform: ironrdp_pdu::rdp::capability_sets::MajorPlatformType::ANDROID,
        hardware_id: None,
        request_data: None,
        autologon: true,
        enable_audio_playback: false,
        performance_flags: ironrdp_pdu::rdp::client_info::PerformanceFlags::default(),
        license_cache: None,
        timezone_info: Default::default(),
        // Request server-side pointer (cursor) updates so the RDP viewer can
        // draw a cursor like VNC does (#212). Keep software rendering off — we
        // composite the cursor client-side over the framebuffer rather than
        // having ironrdp bake it in, so it tracks the touchpad-mode virtual
        // cursor and doesn't leave trails on slow links.
        enable_server_pointer: true,
        pointer_software_rendering: false,
    }
}

/// Translate a rustls handshake error into a human-readable string that
/// names the actual failure mode rather than the generic "TLS handshake
/// failed". The Kotlin layer pattern-matches on these strings (see
/// `RdpViewModel.describeError`) to render an actionable user message.
///
/// We pull out the cases users actually hit with non-Windows RDP servers:
///   - cipher / kx-group / sig-scheme not in common (peer-incompatible)
///   - TLS-version mismatch
///   - HandshakeFailure alert from peer (often the server-side equivalent
///     of "no shared cipher")
///   - certificate problems (algorithm, expiry, name mismatch)
///   - peer protocol misbehaviour
///
/// Anything we don't specifically recognise falls through to a `{:?}` dump
/// so unknown variants still leave a usable trail in bug reports. (#109)
fn diagnose_tls_error(e: &rustls::Error) -> String {
    use rustls::Error;
    match e {
        Error::PeerIncompatible(reason) => format!(
            "no shared TLS parameters with server ({:?}) — \
            Haven uses the rustls/ring crypto provider which supports a narrower \
            cipher set than OpenSSL/SChannel. The server may need ECDHE-RSA + \
            AES-GCM (TLS 1.2 or 1.3) enabled.",
            reason
        ),
        Error::AlertReceived(alert) => format!(
            "server sent TLS alert ({:?}). HandshakeFailure here usually means \
            the server has no cipher suite in common with us.",
            alert
        ),
        Error::InvalidCertificate(cert_err) => format!(
            "server certificate problem ({:?})",
            cert_err
        ),
        Error::PeerMisbehaved(reason) => format!(
            "server misbehaved during TLS handshake ({:?})",
            reason
        ),
        Error::NoApplicationProtocol => {
            "server requires an ALPN protocol Haven doesn't advertise".to_string()
        }
        Error::InappropriateMessage { expect_types, got_type } => format!(
            "unexpected TLS message: expected {:?}, got {:?}",
            expect_types, got_type
        ),
        Error::InappropriateHandshakeMessage { expect_types, got_type } => format!(
            "unexpected TLS handshake message: expected {:?}, got {:?}",
            expect_types, got_type
        ),
        other => format!("{:?}", other),
    }
}

/// Translate an ironrdp `connect_finalize` error into a human-readable
/// string. Walks the structured error kind first (so we can match on
/// CredSSP / Negotiation / AccessDenied without substring sniffing),
/// falls back to a `{:?}` dump for anything we don't recognise.
///
/// Output is consumed by `RdpViewModel.describeError` on the Kotlin
/// side, which adds workaround hints (e.g. "try unchecking NLA")
/// keyed off the leading classification word.
fn diagnose_finalize_error(e: &ironrdp_connector::ConnectorError) -> String {
    use ironrdp_connector::{ConnectorErrorKind, sspi};

    let raw = format!("{:?}", e);
    let kind_str = match e.kind() {
        ConnectorErrorKind::Credssp(sspi_err) => {
            let inner = diagnose_credssp_error(sspi_err);
            // Tag with "Credssp:" so the Kotlin classifier can pivot
            // on "Authentication" vs "TLS" cleanly.
            format!("Authentication failed (CredSSP): {}", inner)
        }
        ConnectorErrorKind::Negotiation(failure) => {
            format!("RDP security negotiation failed: {}", failure)
        }
        ConnectorErrorKind::AccessDenied => {
            "Authentication failed: server denied access".to_string()
        }
        ConnectorErrorKind::Encode(enc) => {
            format!("RDP protocol encode error: {:?}", enc)
        }
        ConnectorErrorKind::Decode(dec) => {
            format!("RDP protocol decode error: {:?}", dec)
        }
        ConnectorErrorKind::Reason(r) => {
            format!("RDP connect finalize failed: {}", r)
        }
        ConnectorErrorKind::Custom | ConnectorErrorKind::General => {
            classify_raw_finalize(&raw)
        }
        // ConnectorErrorKind is #[non_exhaustive] in ironrdp 0.8 — catch
        // any future-added variants by falling back to substring sniffing.
        _ => classify_raw_finalize(&raw),
    };
    let _ = sspi::ErrorKind::OutOfSequence; // suppress unused-import warning
    kind_str
}

/// Substring-sniffing fallback for ConnectorErrorKind variants we don't
/// handle structurally (Custom / General catch-alls and any future
/// non-exhaustive additions).
///
/// **Phase invariant:** this function only sees errors from
/// `connect_finalize`. By that point the TLS handshake has already
/// completed (driven by `complete_io` on line ~679 of `run_rdp_session`)
/// and, if NLA is on, CredSSP has also completed. So **any** failure
/// reaching here is post-handshake — never a TLS handshake failure
/// itself. Older versions of this fallback labelled the rustls
/// "unexpected EOF" path as "TLS handshake failed", which sent users
/// looking at the wrong layer (#TODO file follow-up).
fn classify_raw_finalize(raw: &str) -> String {
    if raw.contains("AlertReceived(InternalError)") ||
        raw.contains("AlertReceived(AccessDenied)") ||
        raw.contains("AlertReceived(BadCertificate)")
    {
        format!(
            "Authentication failed: server rejected credentials \
            (check username, password, and domain). {}",
            raw
        )
    } else if raw.contains("UnexpectedEof") ||
        raw.contains("peer closed connection without sending TLS close_notify")
    {
        // Server closed the TCP socket during RDP setup, *after* TLS
        // (and CredSSP if NLA was on) had already succeeded. Most
        // common trigger we've seen: the profile's colour depth is 16
        // and the server is modern Windows — once we set
        // SUPPORT_DYN_VC_GFX_PROTOCOL on the early-cap flag, Windows
        // TCP-FINs the connection if the GCC core's legacy
        // color_depth is Bpp8. Setting the profile's colour depth to
        // 32 fixes it. (v5.24.69+; auto-bumped by Migration 40_41 for
        // NLA-on profiles in v5.24.70.)
        format!(
            "Server closed the connection during RDP setup (after \
            TLS + authentication succeeded). Most common cause: the \
            profile's colour depth is 16 against a modern Windows \
            server — try 32. ({})",
            raw
        )
    } else if raw.contains("Tls") || raw.contains("TLS") || raw.contains("unexpected_message") {
        // TLS-related error after the handshake — rustls alert during
        // CredSSP IO, mid-session protocol error, etc. Distinct from a
        // handshake failure (which would have surfaced earlier from
        // complete_io).
        format!("Server sent a TLS error during RDP setup: {}", raw)
    } else {
        format!("RDP connect finalize failed: {}", raw)
    }
}

/// Translate an sspi-rs CredSSP error into a human-readable string.
/// MessageAltered specifically maps to "could not verify a public key
/// hash" — typically caused by a mismatch between the client's view of
/// the TLS server certificate's public key bytes and the server's
/// (most often hit against gnome-remote-desktop / FreeRDP server with
/// certain certificate types). LogonDenied = wrong credentials.
fn diagnose_credssp_error(e: &ironrdp_connector::sspi::Error) -> String {
    use ironrdp_connector::sspi::ErrorKind;
    let kind_label = match e.error_type {
        ErrorKind::LogonDenied => "wrong username or password",
        ErrorKind::MessageAltered => {
            "server rejected the public-key hash — \
            this typically means the server's CredSSP impl computed a \
            different SHA-256 over the TLS certificate's public key than \
            Haven did. Try unchecking 'Network Level Authentication' on \
            the connection profile (Linux gnome-remote-desktop is the \
            usual offender)"
        }
        ErrorKind::IncompleteCredentials => "incomplete credentials",
        ErrorKind::NoCredentials => "no credentials provided",
        ErrorKind::InvalidToken => "server returned invalid CredSSP token",
        ErrorKind::OutOfSequence => "CredSSP messages out of sequence",
        ErrorKind::TimeSkew => "system clock differs from server's by too much",
        _ => "CredSSP failed",
    };
    format!("{} ({:?}: {})", kind_label, e.error_type, e.description)
}

/// Lowercase-hex SHA-256 of a byte slice. Matches Kotlin's
/// `TlsCertVerifier.fingerprint` (SHA-256 of the DER leaf certificate).
fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    Sha256::digest(bytes).iter().map(|b| format!("{:02x}", b)).collect()
}

/// Extract the SubjectPublicKeyInfo (full DER) from a server certificate with
/// the lenient x509-cert parser. webpki's cert parser rejects any non-v3
/// certificate outright (Error::UnsupportedCertVersion), but plenty of RDP
/// servers — VirtualBox VRDP in particular — present a self-signed X.509 *v1*
/// certificate. Since we pin the leaf by fingerprint and never chain-validate,
/// all we need from the cert is its public key, and x509-cert parses v1 fine.
/// This is the same parser already used post-handshake to feed CredSSP. (#422)
fn spki_from_cert(
    cert: &rustls::pki_types::CertificateDer<'_>,
) -> Result<rustls::pki_types::SubjectPublicKeyInfoDer<'static>, rustls::Error> {
    use x509_cert::der::{Decode as _, Encode as _};
    let parsed = x509_cert::Certificate::from_der(cert.as_ref())
        .map_err(|e| rustls::Error::General(format!("parse server certificate: {e}")))?;
    let spki_der = parsed
        .tbs_certificate()
        .subject_public_key_info()
        .to_der()
        .map_err(|e| rustls::Error::General(format!("re-encode server SPKI: {e}")))?;
    Ok(rustls::pki_types::SubjectPublicKeyInfoDer::from(spki_der))
}

/// Verify a TLS handshake signature against a bare SubjectPublicKeyInfo instead
/// of webpki's full-certificate path (which rejects non-v3 certs before it ever
/// looks at the key). This still proves the server holds the private key for the
/// pinned certificate, so it preserves the MITM protection the pin relies on;
/// only the cert *parse* is relaxed. Mirrors rustls's own scheme→algorithm
/// selection: TLS 1.2 tries every candidate algorithm for the scheme, TLS 1.3
/// only the first. (#422)
fn verify_handshake_sig_raw(
    message: &[u8],
    spki: &rustls::pki_types::SubjectPublicKeyInfoDer<'_>,
    scheme: rustls::SignatureScheme,
    signature: &[u8],
    algs: &rustls::crypto::WebPkiSupportedAlgorithms,
    tls13: bool,
) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
    let raw = webpki::RawPublicKeyEntity::try_from(spki)
        .map_err(|e| rustls::Error::General(format!("parse server public key: {e:?}")))?;
    let candidates = algs
        .mapping
        .iter()
        .find(|(s, _)| *s == scheme)
        .map(|(_, a)| *a)
        .ok_or_else(|| rustls::Error::General(format!("unsupported signature scheme {scheme:?}")))?;
    // TLS 1.3 mandates a single scheme→algorithm mapping; TLS 1.2 allows several.
    let candidates = if tls13 { &candidates[..1] } else { candidates };
    let mut last_err = None;
    for alg in candidates {
        match raw.verify_signature(*alg, message, signature) {
            Ok(()) => return Ok(rustls::client::danger::HandshakeSignatureValid::assertion()),
            Err(e) => last_err = Some(e),
        }
    }
    Err(rustls::Error::General(format!(
        "server handshake signature verification failed: {last_err:?}"
    )))
}

/// rustls verifier that pins the server's leaf certificate (trust-on-first-use)
/// instead of accepting any certificate. RDP servers overwhelmingly use
/// self-signed certificates, so a chain-of-trust check is not viable; we pin
/// the exact leaf fingerprint the way SSH pins host keys.
///
/// [pinned] is the fingerprint remembered on a prior connection (or None on
/// first use). Identity is checked in `verify_server_cert`; crucially, key
/// possession is still verified for real in `verify_tls*_signature` (delegated
/// to the crypto provider) so a MITM cannot replay the victim's public cert
/// without its private key. (security-review critical #2)
struct PinnedCertVerifier {
    pinned: Option<String>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl std::fmt::Debug for PinnedCertVerifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PinnedCertVerifier")
            .field("pinned", &self.pinned)
            .finish()
    }
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let observed = sha256_hex(end_entity.as_ref());
        match &self.pinned {
            Some(p) if !p.eq_ignore_ascii_case(&observed) => Err(rustls::Error::General(format!(
                "server TLS certificate has changed since the last connection \
                 (pinned {}…, got {}…) — refusing to continue, as this can \
                 indicate a man-in-the-middle attack. If you changed the \
                 server's certificate on purpose, forget the saved certificate \
                 for this host and reconnect.",
                p.chars().take(16).collect::<String>(),
                observed.chars().take(16).collect::<String>(),
            ))),
            // Pinned & matching, or first use (TOFU): accept the identity here;
            // key possession is proven by the signature checks below.
            _ => Ok(rustls::client::danger::ServerCertVerified::assertion()),
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        // Raw-key verification (not webpki's full-cert path) so X.509 v1 server
        // certs are accepted; key possession is still proven. (#422)
        let spki = spki_from_cert(cert)?;
        verify_handshake_sig_raw(
            message,
            &spki,
            dss.scheme,
            dss.signature(),
            &self.provider.signature_verification_algorithms,
            false,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        let spki = spki_from_cert(cert)?;
        verify_handshake_sig_raw(
            message,
            &spki,
            dss.scheme,
            dss.signature(),
            &self.provider.signature_verification_algorithms,
            true,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// Create a rustls TLS connector that pins the server's leaf certificate
/// (trust-on-first-use). [pinned_cert_sha256] is the fingerprint remembered
/// from a previous connection, or None on first use.
fn create_tls_config(pinned_cert_sha256: Option<String>) -> Result<rustls::ClientConfig, RdpError> {
    // Explicitly use ring provider — auto-detection panics on Android
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(PinnedCertVerifier {
        pinned: pinned_cert_sha256,
        provider: Arc::clone(&provider),
    });
    let mut config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|_| RdpError::TlsError)?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    // Honour SSLKEYLOGFILE (no-op when the env var is unset). Useful for host
    // wireshark debugging via rdp-cli; on device the env var is never set.
    config.key_log = Arc::new(rustls::KeyLogFile::new());
    Ok(config)
}

#[cfg(test)]
mod tls_pin_tests {
    use super::*;
    use rustls::client::danger::ServerCertVerifier;

    fn verifier(pinned: Option<&str>) -> PinnedCertVerifier {
        PinnedCertVerifier {
            pinned: pinned.map(|s| s.to_string()),
            provider: Arc::new(rustls::crypto::ring::default_provider()),
        }
    }

    fn check(v: &PinnedCertVerifier, der: &[u8]) -> Result<(), rustls::Error> {
        let cert = rustls::pki_types::CertificateDer::from(der.to_vec());
        let sn = rustls::pki_types::ServerName::try_from("example.com").unwrap();
        let now = rustls::pki_types::UnixTime::since_unix_epoch(
            std::time::Duration::from_secs(1_700_000_000),
        );
        v.verify_server_cert(&cert, &[], &sn, &[], now).map(|_| ())
    }

    #[test]
    fn sha256_hex_matches_known_vector() {
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn first_use_is_accepted() {
        // No pin (TOFU first connect) → identity accepted.
        assert!(check(&verifier(None), &[1, 2, 3]).is_ok());
    }

    #[test]
    fn matching_pin_is_accepted() {
        let der = [10u8, 20, 30];
        let v = verifier(Some(&sha256_hex(&der)));
        assert!(check(&v, &der).is_ok());
    }

    #[test]
    fn changed_cert_is_rejected() {
        // Pinned to cert A, server presents cert B → reject (MITM signal).
        let v = verifier(Some(&sha256_hex(&[1, 2, 3])));
        assert!(check(&v, &[9, 9, 9]).is_err());
    }

    #[test]
    fn pin_comparison_is_case_insensitive() {
        let der = [5u8, 6, 7];
        let v = verifier(Some(&sha256_hex(&der).to_uppercase()));
        assert!(check(&v, &der).is_ok());
    }

    // --- #422: accept X.509 v1 server certs (VirtualBox VRDP) ---

    // Real self-signed X.509 v1 cert (RSA-2048) + an rsa_pss_rsae_sha256
    // signature by its key over V1_MSG, minted with asn1crypto. VirtualBox
    // VRDP presents a v1 cert exactly like this.
    const V1_CERT: &[u8] = include_bytes!("../tests/data/v1-cert.der");
    const V1_SIG: &[u8] = include_bytes!("../tests/data/v1-cert-pss-sha256.sig");
    const V1_MSG: &[u8] = b"haven rdp #422 handshake transcript";

    fn ring_algs() -> rustls::crypto::WebPkiSupportedAlgorithms {
        rustls::crypto::ring::default_provider().signature_verification_algorithms
    }

    #[test]
    fn webpki_rejects_v1_cert_but_x509_cert_parses_it() {
        // The #422 mechanism: webpki's full-cert path rejects the v1 cert (this
        // is what broke the handshake) — our lenient extraction accepts it.
        let der = rustls::pki_types::CertificateDer::from(V1_CERT.to_vec());
        assert!(
            webpki::EndEntityCert::try_from(&der).is_err(),
            "expected webpki to reject the v1 cert"
        );
        assert!(spki_from_cert(&der).is_ok(), "spki_from_cert should parse v1");
    }

    #[test]
    fn raw_key_verifies_genuine_signature_from_v1_cert() {
        let der = rustls::pki_types::CertificateDer::from(V1_CERT.to_vec());
        let spki = spki_from_cert(&der).expect("v1 SPKI");
        assert!(
            verify_handshake_sig_raw(
                V1_MSG,
                &spki,
                rustls::SignatureScheme::RSA_PSS_SHA256,
                V1_SIG,
                &ring_algs(),
                true,
            )
            .is_ok(),
            "a genuine signature from the pinned v1 cert's key must verify"
        );
    }

    #[test]
    fn raw_key_rejects_tampered_signature() {
        // MITM without the private key cannot forge the handshake signature,
        // even though the cert parse is now relaxed.
        let der = rustls::pki_types::CertificateDer::from(V1_CERT.to_vec());
        let spki = spki_from_cert(&der).expect("v1 SPKI");
        let mut bad = V1_SIG.to_vec();
        bad[0] ^= 0xff;
        assert!(verify_handshake_sig_raw(
            V1_MSG,
            &spki,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            &bad,
            &ring_algs(),
            true,
        )
        .is_err());
    }

    #[test]
    fn raw_key_rejects_signature_over_wrong_message() {
        let der = rustls::pki_types::CertificateDer::from(V1_CERT.to_vec());
        let spki = spki_from_cert(&der).expect("v1 SPKI");
        assert!(verify_handshake_sig_raw(
            b"not the signed transcript",
            &spki,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            V1_SIG,
            &ring_algs(),
            true,
        )
        .is_err());
    }
}

/// Run the blocking RDP session on a dedicated thread.
///
/// Returns the raw error message on failure so Kotlin can surface it to the
/// user rather than swallowing every failure into a generic "Connection
/// failed" — which is what happened before and produced the "nothing
/// happens, empty Desktop screen" symptom in #106.
/// #422: re-express a fast-path input event as its slow-path TS_INPUT_EVENT
/// twin, for servers that never negotiated fast-path input (VirtualBox VRDP).
/// The mouse PDU bodies are byte-identical between the two paths; keyboard and
/// sync events just carry their flags in different positions/widths. QoE has
/// no slow-path equivalent and is dropped.
fn slow_path_input_event(
    ev: &ironrdp_pdu::input::fast_path::FastPathInputEvent,
) -> Option<ironrdp_pdu::input::InputEvent> {
    use ironrdp_pdu::input::fast_path::{FastPathInputEvent as Fp, KeyboardFlags as FpKb};
    use ironrdp_pdu::input::{InputEvent, scan_code, sync, unicode};

    Some(match ev {
        Fp::KeyboardEvent(flags, code) => {
            let mut kf = scan_code::KeyboardFlags::empty();
            if flags.contains(FpKb::RELEASE) {
                kf |= scan_code::KeyboardFlags::RELEASE;
            }
            if flags.contains(FpKb::EXTENDED) {
                kf |= scan_code::KeyboardFlags::EXTENDED;
            }
            if flags.contains(FpKb::EXTENDED1) {
                kf |= scan_code::KeyboardFlags::EXTENDED_1;
            }
            InputEvent::ScanCode(scan_code::ScanCodePdu {
                flags: kf,
                key_code: u16::from(*code),
            })
        }
        Fp::UnicodeKeyboardEvent(flags, code) => {
            let mut kf = unicode::KeyboardFlags::empty();
            if flags.contains(FpKb::RELEASE) {
                kf |= unicode::KeyboardFlags::RELEASE;
            }
            InputEvent::Unicode(unicode::UnicodePdu {
                flags: kf,
                unicode_code: *code,
            })
        }
        Fp::MouseEvent(pdu) => InputEvent::Mouse(pdu.clone()),
        Fp::MouseEventEx(pdu) => InputEvent::MouseX(pdu.clone()),
        Fp::MouseEventRel(pdu) => InputEvent::MouseRel(pdu.clone()),
        Fp::SyncEvent(flags) => InputEvent::Sync(sync::SyncPdu {
            flags: sync::SyncToggleFlags::from_bits_retain(u32::from(flags.bits())),
        }),
        Fp::QoeEvent(_) => return None,
    })
}

/// Runs one RDP connection to completion. `Ok(None)` is a normal session end;
/// `Ok(Some(plan))` means the server sent a redirection this connection did
/// not follow yet — the caller reconnects and passes the plan back in as
/// `redirect` (#117). A session that IS the followed hop never returns a
/// second plan; a re-redirect fails instead.
fn run_rdp_session(
    stream: TcpStream,
    config: &RdpConfig,
    state: &Arc<RwLock<SessionState>>,
    input_queue: &Arc<Mutex<Vec<InputEvent>>>,
    server_name: &str,
    server_addr: SocketAddr,
    redirect: Option<&redirection::RedirectFollow>,
) -> Result<Option<redirection::RedirectFollow>, String> {
    use ironrdp_blocking::{connect_begin, connect_finalize, mark_as_upgraded, Framed};
    use ironrdp_connector::ServerName;
    use ironrdp_session::ActiveStageOutput;
    use ironrdp_session::image::DecodedImage;
    use ironrdp_graphics::image_processing::PixelFormat;

    let mut rdp_config = build_config(config);
    // #117: a followed redirection replays the server's routing token in the
    // X.224 Connection Request and authenticates with the one-time
    // credentials from the redirection PDU (GNOME Remote Desktop generates a
    // random user/password pair per handover — the profile's own credentials
    // would be refused).
    if let Some(follow) = redirect {
        rdp_config.request_data = Some(ironrdp_pdu::nego::NegoRequestData::routing_token(
            follow.routing_token.clone(),
        ));
        remember_secret(&follow.routing_token);
        if let (Some(u), Some(p)) = (&follow.username, &follow.password) {
            remember_secret(u);
            remember_secret(p);
            rdp_config.credentials = ironrdp_connector::Credentials::UsernamePassword {
                username: u.clone(),
                password: p.clone(),
            };
            rdp_config.domain = follow.domain.clone();
        }
        info!(
            "#117: following server redirection — routing token {} chars, one-time credentials: {}",
            follow.routing_token.len(),
            follow.username.is_some(),
        );
    }
    // DisplayControl must be registered even though we never resize: xrdp
    // opens the channel unconditionally and aborts ALL channel processing
    // (dynamic_monitor_open_response: error) if the client refuses it —
    // EGFX then never delivers a single frame (blank desktop against
    // EGFX-capable xrdp). Reply to the server caps with a one-monitor
    // layout matching the session size, as MS-RDPEDISP expects.
    let (dc_width, dc_height) = (config.width as u32 & !1, config.height as u32);
    let display_control =
        ironrdp_displaycontrol::client::DisplayControlClient::new(move |_caps| {
            let layout =
                ironrdp_displaycontrol::pdu::DisplayControlMonitorLayout::new_single_primary_monitor(
                    dc_width, dc_height, None, None,
                )
                .map_err(|e| ironrdp_pdu::encode_err!(e))?;
            let pdu: ironrdp_displaycontrol::pdu::DisplayControlPdu = layout.into();
            Ok(vec![Box::new(pdu)])
        });
    let mut connector = ironrdp_connector::ClientConnector::new(rdp_config, server_addr)
        .with_static_channel(
            ironrdp_dvc::DrdynvcClient::new()
                .with_dynamic_channel(crate::egfx::EgfxProcessor::new(
                    state.clone(),
                    config.progressive_upgrade,
                    config.avc_enabled,
                ))
                .with_dynamic_channel(display_control),
        );

    // Keep a clone of the underlying TCP stream so we can adjust the read
    // timeout out-of-band without having to reach through the TLS wrap.
    // Used post-handshake to shrink the 30s handshake timeout back to
    // 100ms for responsive shutdown polling in the session loop.
    let stream_ctl = stream.try_clone()
        .map_err(|e| format!("TcpStream::try_clone failed: {}", e))?;

    // Phase 1: Connection initiation (pre-TLS)
    let mut framed = Framed::new(stream);
    let should_upgrade = connect_begin(&mut framed, &mut connector)
        .map_err(|e| format!("RDP negotiation failed: {:?}", e))?;

    // Phase 2: TLS upgrade. Pin the server cert (TOFU) against the fingerprint
    // remembered from a previous connection, if any (security-review #2).
    let tls_config = create_tls_config(config.pinned_cert_sha256.clone())
        .map_err(|e| format!("TLS configuration failed: {}", e))?;
    let (raw_stream, leftover) = framed.into_inner();

    let server_name_ref = rustls::pki_types::ServerName::try_from(server_name.to_string())
        .unwrap_or_else(|_| rustls::pki_types::ServerName::IpAddress(
            server_addr.ip().into()
        ));

    let mut tls_conn = rustls::ClientConnection::new(
        Arc::new(tls_config),
        server_name_ref,
    ).map_err(|e| format!("TLS connector init failed: {}", e))?;

    // Drive the TLS handshake to completion *before* reading the server
    // certificate. rustls is lazy: peer_certificates() returns None
    // until the first IO triggers the handshake. Without this we passed
    // an empty buffer as `server_public_key`, CredSSP's pub_key_auth
    // hash never matched the server, and Windows tore down the TLS
    // session with `internal_error` (#106 / #109). Fix verified against
    // a Windows Server 2025 Datacenter VM with strict NLA.
    let mut socket = raw_stream;
    if let Err(e) = tls_conn.complete_io(&mut socket) {
        // io::Error from complete_io wraps a rustls::Error — fish it out so
        // we can surface the specific failure mode (cipher mismatch, version
        // mismatch, cert problem, alert-from-peer) rather than collapsing
        // every TLS failure into a single opaque "TLS handshake failed".
        // See diagnose_tls_error for the mapping. (#109 follow-up)
        let inner = e.get_ref().and_then(|i| i.downcast_ref::<rustls::Error>());
        let detail = match inner {
            Some(rustls_err) => diagnose_tls_error(rustls_err),
            None => format!("{}", e),
        };
        return Err(format!("TLS handshake failed: {}", detail));
    }

    let tls_shared = SharedTls::new(rustls::StreamOwned::new(tls_conn, socket));
    let mut tls_framed = Framed::new_with_leftover(tls_shared.clone(), leftover);

    let upgraded = mark_as_upgraded(should_upgrade, &mut connector);

    // Phase 3: Extract the SubjectPublicKey BIT STRING bits — *not* the
    // full DER of the leaf certificate. CredSSP's pub_key_auth hashes
    // these exact bytes (SHA-256 of `magic || nonce || subject_public_key`)
    // and the server independently computes the same hash from its own
    // SPKI; if we feed it the full cert DER, the hashes never match and
    // Server 2025 disconnects with TLS internal_error. Older Windows
    // versions weren't strict enough to catch the mismatch — but it was
    // always wrong. Reproducer (Devolutions/sspi-rs#651) shows full-DER
    // → AlertReceived(InternalError) and SPKI → success against the
    // same VM with the same credentials.
    let raw_cert_der = tls_shared
        .lock()
        .conn
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| cert.as_ref().to_vec())
        .ok_or_else(|| "no peer certificate after TLS handshake".to_string())?;

    // Report the leaf-certificate fingerprint so Kotlin can pin it (TOFU). A
    // *changed* cert was already rejected inside the handshake above (before
    // any credentials were sent); this callback fires only when the cert
    // matched the pin or this is the first connection. (security-review #2)
    {
        let cert_fp = sha256_hex(&raw_cert_der);
        if let Some(cb) = state.read().ok().and_then(|s| s.session_callback.clone()) {
            cb.on_server_cert(cert_fp);
        }
    }

    let server_public_key = {
        use x509_cert::der::Decode as _;
        let cert = x509_cert::Certificate::from_der(&raw_cert_der)
            .map_err(|e| format!("parse server cert: {}", e))?;
        cert.tbs_certificate()
            .subject_public_key_info()
            .subject_public_key
            .as_bytes()
            .ok_or_else(|| "subject public key BIT STRING is not byte-aligned".to_string())?
            .to_vec()
    };

    // Phase 4: CredSSP + remaining connection sequence
    let sname = ServerName::new(server_name.to_string());

    // No-op network client (reqwest not available on Android).
    // CredSSP's NTLM path doesn't make network calls; only Kerberos does.
    struct NoopNetworkClient;
    impl ironrdp_connector::sspi::network_client::NetworkClient for NoopNetworkClient {
        fn send(&self, _request: &ironrdp_connector::sspi::NetworkRequest) -> ironrdp_connector::sspi::Result<Vec<u8>> {
            Err(ironrdp_connector::sspi::Error::new(
                ironrdp_connector::sspi::ErrorKind::NoAuthenticatingAuthority,
                "Network client not available on Android",
            ))
        }
    }
    let mut network_client = NoopNetworkClient;

    let connection_result = connect_finalize(
        upgraded,
        connector,
        &mut tls_framed,
        &mut network_client,
        sname,
        server_public_key,
        None, // no Kerberos config
    ).map_err(|e| diagnose_finalize_error(&e))?;

    // Session is connected
    let fb_width = connection_result.desktop_size.width;
    let fb_height = connection_result.desktop_size.height;
    info!("RDP connected, desktop {}x{}", fb_width, fb_height);

    let mut image = DecodedImage::new(PixelFormat::RgbA32, fb_width, fb_height);

    let (resize_cb, session_cb) = {
        let mut s = state.write().map_err(|_| "session state lock poisoned".to_string())?;
        s.connected = true;
        s.framebuffer = Some(FrameData {
            width: fb_width,
            height: fb_height,
            pixels: vec![0u8; fb_width as usize * fb_height as usize * 4],
        });
        (s.frame_callback.clone(), s.session_callback.clone())
    };
    // Invoke callbacks outside the lock so Kotlin handlers that call back
    // into getFramebuffer() don't deadlock on the state RwLock.
    if let Some(cb) = session_cb {
        cb.on_connected(fb_width, fb_height);
    }
    if let Some(cb) = resize_cb {
        cb.on_resize(fb_width, fb_height);
    }

    // #438: keep a resettable copy of the activation sequence so a bare
    // Server Demand Active (FreeRDP shadow skips the preceding Deactivate
    // All) can re-enter the activation state machine mid-session.
    // connector 0.10.0 replaced the clone-and-reset dance with a factory that
    // mints a fresh sequence on demand — which is what #438 wanted in the first
    // place.
    let activation_factory = connection_result.activation_factory;

    // #422: fast-path input is only legal when the server advertised it
    // (MS-RDPBCGR 2.2.8.1.2). VirtualBox VRDP advertises SCANCODES only and
    // closes the connection on a lone fast-path scancode event ("Network
    // packet length is incorrect 0x0004" in VBox.log) — the reason arrow
    // keys killed VRDE sessions. Such servers get slow-path TS_INPUT_PDUs,
    // as mstsc/FreeRDP do.
    // #422: does the server accept TS_UNICODE_KEYBOARD_EVENT at all?
    let unicode_input_supported = {
        use ironrdp_pdu::rdp::capability_sets::InputFlags;
        connection_result.input_flags.contains(InputFlags::UNICODE)
    };
    let unicode_dropped = Arc::new(std::sync::atomic::AtomicU64::new(0));
    if !unicode_input_supported {
        info!("Server did not advertise unicode input; non-ASCII characters cannot be sent");
    }

    let fastpath_input_supported = {
        use ironrdp_pdu::rdp::capability_sets::InputFlags;
        let flags = connection_result.input_flags;
        let supported = flags.intersects(InputFlags::FASTPATH_INPUT | InputFlags::FASTPATH_INPUT_2);
        if !supported {
            info!("Server did not advertise fast-path input ({flags:?}); using slow-path input PDUs");
        }
        supported
    };

    // session 0.11.0 replaced ActiveStage::new(connection_result) with an
    // explicit builder; the fields are the same ones the constructor read.
    let mut active_stage = ironrdp_session::ActiveStageBuilder {
        static_channels: connection_result.static_channels,
        user_channel_id: connection_result.user_channel_id,
        io_channel_id: connection_result.io_channel_id,
        message_channel_id: connection_result.message_channel_id,
        share_id: connection_result.share_id,
        compression_type: connection_result.compression_type,
        enable_server_pointer: connection_result.enable_server_pointer,
        pointer_software_rendering: connection_result.pointer_software_rendering,
    }
    .build();

    // Handshake is done; shrink the read timeout to 100ms so the session
    // loop can poll the shutdown flag promptly. WouldBlock/TimedOut are
    // handled by `continue` in the loop below.
    if let Err(e) = stream_ctl.set_read_timeout(Some(std::time::Duration::from_millis(100))) {
        error!("set_read_timeout post-handshake failed (non-fatal): {}", e);
    }

    // #422: a VirtualBox guest whose virtual display has gone to sleep sends a
    // new client NOTHING — VRDP only pushes dirty regions, a sleeping display
    // produces none, and connecting does not invalidate the retained frame.
    // Only a KEYBOARD event wakes the guest display (device-verified: 10s of
    // pointer motion is filtered as noise, a lone Ctrl tap repaints within
    // ~150ms via Display::i_handleDisplayResize). Touch clients never send
    // keys naturally, so without this the session stays black forever.
    //
    // The wake fires only when NO visual update has arrived for the whole
    // grace window after activation — i.e. the screen is provably blank — so
    // it can never inject a key into a session the user can see.
    // HAVEN_RDP_NO_WAKE=1 disables it.
    let wake_enabled = std::env::var("HAVEN_RDP_NO_WAKE").as_deref() != Ok("1");
    let wake_deadline = std::time::Instant::now() + std::time::Duration::from_millis(1500);
    let mut wake_sent = false;
    // Deliberately NOT perf.frames — the perf counters reset on every periodic
    // report, which made this gate re-arm after each report window.
    let mut any_visual_update = false;

    // Input state tracking
    let mut input_db = ironrdp_input::Database::new();
    let mut input_dumps = 0usize;
    info!(
        "TEMP #422: share_id={} user_channel={} io_channel={}",
        connection_result.share_id, connection_result.user_channel_id, connection_result.io_channel_id
    );

    // #477: on a fast-path server, move input off the session loop entirely.
    //
    // Encoding fast-path input is a pure function of the events — ActiveStage's
    // `process_fastpath_input` only reaches for the image to composite a
    // client-side pointer, and Haven sets `pointer_software_rendering: false`
    // so `image.pointer` is never populated and `move_pointer` is a no-op that
    // records coordinates nobody reads (we draw the cursor in Kotlin, #212).
    // That leaves the socket as the only thing input needs, so it can run on
    // its own thread and stop waiting behind frame decodes.
    //
    // Slow-path servers (VirtualBox VRDP, #422) keep the in-loop path below:
    // their PDUs go through `active_stage.encode_static`, which is not shareable.
    let input_counters = Arc::new(InputCounters::default());
    let stop_input = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let input_thread = if fastpath_input_supported {
        let queue = Arc::clone(input_queue);
        let mut sink = tls_shared.clone();
        let st = Arc::clone(state);
        let stop = Arc::clone(&stop_input);
        let counters = Arc::clone(&input_counters);
        // The thread keeps its own key/button state. `input_db` above is still
        // fresh at this point and is only touched by the slow-path branch,
        // which never runs while this thread exists.
        let mut db = ironrdp_input::Database::new();
        let uni_ok = unicode_input_supported;
        let uni_dropped = Arc::clone(&unicode_dropped);
        Some(std::thread::spawn(move || {
            use ironrdp_pdu::input::fast_path::FastPathInput;
            use std::io::Write as _;
            use std::sync::atomic::Ordering;
            loop {
                if stop.load(Ordering::Acquire) || st.read().map(|s| s.shutdown).unwrap_or(true) {
                    break;
                }
                // Scope the guard: a `match queue.lock() { .. }` holds it for the
                // whole match, so sleeping in an arm would idle *while holding
                // the queue* and starve the producer — measured as the offered
                // rate collapsing from 60/s to 3.5/s.
                let pending: Vec<InputEvent> = {
                    match queue.lock() {
                        Ok(mut q) => std::mem::take(&mut *q),
                        Err(_) => break,
                    }
                };
                if pending.is_empty() {
                    // 3ms keeps a 60Hz drag from waiting a whole frame without
                    // spinning a core when idle.
                    std::thread::sleep(std::time::Duration::from_millis(3));
                    continue;
                }
                let n = pending.len() as u64;
                let events = fastpath_events_for(&mut db, pending, uni_ok, &uni_dropped);
                if events.is_empty() {
                    continue;
                }
                let frame = match FastPathInput::new(events.to_vec())
                    .map_err(|e| format!("{e}"))
                    .and_then(|pdu| ironrdp_core::encode_vec(&pdu).map_err(|e| format!("{e}")))
                {
                    Ok(f) => f,
                    Err(e) => {
                        error!("Fast-path input encode error: {e}");
                        continue;
                    }
                };
                if let Err(e) = sink.write_all(&frame) {
                    // The session loop owns teardown; a write failing here just
                    // means the connection is going away.
                    debug!("Input write ended: {e}");
                    break;
                }
                counters.flushes.fetch_add(1, Ordering::Relaxed);
                counters.events.fetch_add(n, Ordering::Relaxed);
            }
            debug!("Input thread exiting");
        }))
    } else {
        None
    };

    // #477: queued input can only be flushed once per loop iteration, and each
    // iteration blocks on the socket read above. At a flat 100ms that caps
    // input at ~10 flushes/sec whenever the server is quiet, so a finger drag
    // reaches the server as a burst of moves and then nothing — the local
    // cursor looks smooth while the server's jumps and skips.
    //
    // Poll fast while the user is actually interacting and fall back to the
    // idle timeout afterwards, so an untouched session is not woken 60+ times
    // a second for nothing. Only issued when the value changes; set_read_timeout
    // is a syscall.
    const INPUT_ACTIVE_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(15);
    const IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(100);
    const INPUT_ACTIVE_WINDOW: std::time::Duration = std::time::Duration::from_millis(500);
    let mut last_input_at: Option<std::time::Instant> = None;
    let mut current_timeout = IDLE_TIMEOUT;

    // #466: nothing in the display path was timed, so "it is slow" could not be
    // attributed to a stage. Costs a couple of Instant::now() per frame.
    let mut perf = FramePerf::new(Arc::clone(&input_counters));

    // Consecutive PDUs dropped for a mis-declared length (#422). Reset by any
    // frame that decodes, so this only climbs on a stream that has genuinely
    // gone bad.
    const MAX_SKIPPED_IN_A_ROW: u32 = 100;
    let mut skipped_in_a_row: u32 = 0;

    // Active session loop
    loop {
        // Check for shutdown
        if let Ok(s) = state.read() {
            if s.shutdown {
                break;
            }
        }

        // Process queued input events.
        //
        // With the input thread running (#477) the queue is drained there, so
        // this yields nothing and the adaptive read timeout below is skipped —
        // that existed only to flush input sooner, and the read is no longer
        // what gates input. Slow-path servers still take this path.
        let pending_inputs: Vec<InputEvent> = if input_thread.is_some() {
            Vec::new()
        } else if let Ok(mut q) = input_queue.lock() {
            std::mem::take(&mut *q)
        } else {
            Vec::new()
        };

        if !pending_inputs.is_empty() {
            use std::sync::atomic::Ordering;
            last_input_at = Some(std::time::Instant::now());
            input_counters.flushes.fetch_add(1, Ordering::Relaxed);
            input_counters.events.fetch_add(pending_inputs.len() as u64, Ordering::Relaxed);
        }
        perf.maybe_report();
        if input_thread.is_none() {
            let want_timeout = match last_input_at {
                Some(t) if t.elapsed() < INPUT_ACTIVE_WINDOW => INPUT_ACTIVE_TIMEOUT,
                _ => IDLE_TIMEOUT,
            };
            if want_timeout != current_timeout
                && stream_ctl.set_read_timeout(Some(want_timeout)).is_ok()
            {
                current_timeout = want_timeout;
            }
        }

        // #422: display-wake nudge (see the state setup above the loop). The
        // read timeout is 100ms, so this check runs at least ~10x/second.
        if wake_enabled && !wake_sent && !any_visual_update && std::time::Instant::now() >= wake_deadline {
            wake_sent = true;
            use ironrdp_pdu::input::sync::SyncToggleFlags;
            use ironrdp_pdu::input::{InputEvent, InputEventPdu, ScanCodePdu, SyncPdu, scan_code::KeyboardFlags};
            use ironrdp_pdu::rdp::headers::ShareDataPdu;
            const SC_LCTRL: u16 = 0x1D;
            let events = vec![
                InputEvent::Sync(SyncPdu {
                    flags: SyncToggleFlags::empty(),
                }),
                InputEvent::ScanCode(ScanCodePdu {
                    flags: KeyboardFlags::empty(),
                    key_code: SC_LCTRL,
                }),
                InputEvent::ScanCode(ScanCodePdu {
                    flags: KeyboardFlags::RELEASE,
                    key_code: SC_LCTRL,
                }),
            ];
            let mut buf = ironrdp_core::WriteBuf::new();
            match active_stage.encode_static(&mut buf, ShareDataPdu::Input(InputEventPdu(events))) {
                Ok(_) => match tls_framed.write_all(buf.filled()) {
                    Ok(()) => info!(
                        "#422: no visual update {}ms after activation; sent display-wake nudge (sync + Ctrl tap)",
                        1500
                    ),
                    Err(e) => error!("#422 wake nudge send failed: {e:?}"),
                },
                Err(e) => error!("#422 wake nudge encode failed: {e}"),
            }
        }

        // Slow-path servers only — the fast-path case is handled on the input
        // thread, which leaves `pending_inputs` empty here (#477).
        if !pending_inputs.is_empty() {
            let fastpath_events =
                fastpath_events_for(&mut input_db, pending_inputs, unicode_input_supported, &unicode_dropped);
            // #422: slow-path input for servers that never negotiated fast-path
            // (VirtualBox VRDP). One TS_INPUT_PDU per batch.
            let events: Vec<_> = fastpath_events.iter().filter_map(slow_path_input_event).collect();
            if !events.is_empty() {
                use ironrdp_pdu::input::InputEventPdu;
                use ironrdp_pdu::rdp::headers::ShareDataPdu;
                // The #504 KEY-WIRE per-keystroke dump lived here. It served
                // its purpose — the reporter's 2026-08-18 log showed every
                // RShift+RAlt+letter combo leaving the wire with correct
                // ordering and balanced releases, exonerating this path —
                // and logging every keystroke is a keylogger by another name
                // on a production session (the reporter asked for its
                // removal), so it is gone rather than gated.
                let mut buf = ironrdp_core::WriteBuf::new();
                match active_stage.encode_static(&mut buf, ShareDataPdu::Input(InputEventPdu(events))) {
                    Ok(_) => {
                        // TEMP #422: dump the first few input PDUs on the wire.
                        if input_dumps < 3 {
                            input_dumps += 1;
                            let b = buf.filled();
                            info!(
                                "INPUT-WIRE[{input_dumps}] {} bytes: {}",
                                b.len(),
                                b.iter().map(|x| format!("{x:02x}")).collect::<Vec<_>>().join(" ")
                            );
                        }
                        if let Err(e) = tls_framed.write_all(buf.filled()) {
                            error!("Write input error: {:?}", e);
                        }
                    }
                    Err(e) => {
                        error!("Slow-path input encode error: {:?}", e);
                    }
                }
            }
        }

        // Read server PDU
        let t_read = std::time::Instant::now();
        match tls_framed.read_pdu() {
            Ok((action, frame)) => {
                // Time blocked waiting for the server. Large here means we are
                // NOT the bottleneck; small here with a large process/publish
                // means we are.
                perf.read_us += t_read.elapsed().as_micros() as u64;
                // #425 diag: log action + header bytes to diagnose the KRDP
                // "unexpected channel received: ID 0" interop error.
                if std::env::var("HAVEN_RDP_FRAMEDIAG").is_ok() {
                    let n = frame.len().min(64);
                    debug!("FRAMEDIAG action={:?} len={} bytes={:02x?}", action, frame.len(), &frame[..n]);
                }
                let frame_to_process: &[u8] = &frame;

                let t_process = std::time::Instant::now();
                match active_stage.process(&mut image, action, frame_to_process) {
                    Ok(outputs) => {
                        // Time inside process(): protocol decode, and for AVC
                        // tiles the MediaCodec round-trip plus YUV->RGB, since
                        // the decoder callback runs from in here.
                        perf.process_us += t_process.elapsed().as_micros() as u64;
                        skipped_in_a_row = 0;
                        for output in outputs {
                            match output {
                                ActiveStageOutput::ResponseFrame(response) => {
                                    if let Err(e) = tls_framed.write_all(&response) {
                                        error!("Write response error: {:?}", e);
                                        break;
                                    }
                                }
                                ActiveStageOutput::GraphicsUpdate(rect) => {
                                    debug!("GraphicsUpdate at ({},{}) to ({},{})",
                                        rect.left, rect.top, rect.right, rect.bottom);
                                    any_visual_update = true;
                                    let t_pub = std::time::Instant::now();
                                    update_framebuffer(state, &image, &rect);
                                    perf.publish_us += t_pub.elapsed().as_micros() as u64;
                                    perf.frames += 1;
                                    perf.maybe_report();
                                }
                                // Server cursor updates (#212). Forward the
                                // decoded shape/visibility/position to Kotlin,
                                // firing the callback outside the state lock
                                // (same pattern as update_framebuffer's frame
                                // callback) so a Kotlin handler that calls back
                                // into the client can't deadlock.
                                ActiveStageOutput::PointerBitmap(ptr) => {
                                    let cb = state.read().ok()
                                        .and_then(|s| s.pointer_callback.clone());
                                    if let Some(cb) = cb {
                                        cb.on_pointer_bitmap(
                                            ptr.width,
                                            ptr.height,
                                            ptr.hotspot_x,
                                            ptr.hotspot_y,
                                            ptr.bitmap_data.clone(),
                                        );
                                    }
                                }
                                ActiveStageOutput::PointerHidden => {
                                    let cb = state.read().ok()
                                        .and_then(|s| s.pointer_callback.clone());
                                    if let Some(cb) = cb {
                                        cb.on_pointer_hidden();
                                    }
                                }
                                ActiveStageOutput::PointerDefault => {
                                    let cb = state.read().ok()
                                        .and_then(|s| s.pointer_callback.clone());
                                    if let Some(cb) = cb {
                                        cb.on_pointer_default();
                                    }
                                }
                                ActiveStageOutput::PointerPosition { x, y } => {
                                    let cb = state.read().ok()
                                        .and_then(|s| s.pointer_callback.clone());
                                    if let Some(cb) = cb {
                                        cb.on_pointer_position(x, y);
                                    }
                                }
                                ActiveStageOutput::Terminate(reason) => {
                                    error!("Server disconnect: {}", reason);
                                    break;
                                }
                                // Both new in Devolutions/IronRDP#1501. Logged
                                // rather than acted on: Haven has no session-resume
                                // path to spend the cookie on, and storing a
                                // credential-equivalent token we would never use
                                // is not a trade worth making. Revisit if
                                // reconnect-after-network-drop is ever built.
                                ActiveStageOutput::SaveSessionInfo { logon_complete } => {
                                    debug!("Save Session Info: logon_complete={logon_complete}");
                                }
                                ActiveStageOutput::AutoReconnectCookie(_) => {
                                    debug!("Server issued an auto-reconnect cookie; not retained");
                                }
                                ActiveStageOutput::DeactivateAll => {
                                    // session 0.11.0 stopped handing the
                                    // activation sequence out with this event;
                                    // we mint our own from the factory, which
                                    // is the same sequence it used to pass.
                                    let mut cas = activation_factory.create();
                                    // Server-initiated Deactivation-Reactivation
                                    // (#438): re-run the activation sequence and
                                    // swap onto the renegotiated parameters. Any
                                    // outputs queued after this one belong to the
                                    // pre-deactivation state — drop them.
                                    info!("Server Deactivate All — running reactivation");
                                    let _ = stream_ctl.set_read_timeout(Some(std::time::Duration::from_secs(30)));
                                    let res = perform_reactivation(
                                        &mut tls_framed, &mut cas, None,
                                        &mut active_stage, &mut image, state,
                                    );
                                    let _ = stream_ctl.set_read_timeout(Some(IDLE_TIMEOUT));
                                    // Keep the cached value honest, or the
                                    // adaptive poll above stops re-arming (#477).
                                    current_timeout = IDLE_TIMEOUT;
                                    res?;
                                    break;
                                }
                                // New in session 0.10: multitransport/autodetect
                                // negotiation requests. We advertise neither
                                // (Config sets multitransport_flags: None), so
                                // ignore rather than respond.
                                ActiveStageOutput::MultitransportRequest(_) => {
                                    debug!("Ignoring multitransport request");
                                }
                                ActiveStageOutput::AutoDetect(_) => {
                                    debug!("Ignoring autodetect request");
                                }
                            }
                        }
                    }
                    Err(e) => {
                        let msg = format!("{:?}", e);
                        // A server-redirection PDU (GNOME Remote Desktop, Windows
                        // RDS load-balancers) reaches us as an "unexpected share
                        // control PDU type" because IronRDP can't decode it.
                        // Recognise it from the raw frame and follow it (#117):
                        // hand the replay plan to the caller, which reconnects
                        // with the routing token + one-time credentials. One hop
                        // only — a redirect DURING a followed hop is an error.
                        if let Some(info) = redirection::detect_server_redirect(frame_to_process) {
                            if redirect.is_none() {
                                if let Some(plan) = info.follow_plan() {
                                    info!(
                                        "#117: server redirection received (flags={:#010x}) — reconnecting to follow the handover",
                                        info.redir_flags,
                                    );
                                    return Ok(Some(plan));
                                }
                            }
                            let target = info
                                .target_host()
                                .unwrap_or_else(|| "another session on the same host".to_string());
                            // Reaching here means the redirect was declined: a
                            // second hop, LB_NOREDIRECT, or a token Haven can't
                            // replay. The dump below says which.
                            let reason = format!(
                                "Server requested a session redirection to {target} that Haven \
                                 could not follow (see #117), so the session cannot continue."
                            );
                            warn!("{reason} (redir_flags={:#06x})", info.redir_flags);
                            // #117: one structured dump per redirect so a
                            // reporter's logcat pins down exactly what GRD
                            // sends and the follow step can replay it. The
                            // password is a one-time session credential — log
                            // its length only, never the bytes. The
                            // LoadBalanceInfo routing token is session-scoped
                            // and is the piece whose framing the reconnect
                            // needs verbatim.
                            warn!(
                                "#117 redirect dump: session_id={:#010x} flags={:#010x} no_redirect={} \
                                 target_net={:?} target_fqdn={:?} username={:?} domain={:?} \
                                 password_len={:?} lb_info_len={:?} lb_info_hex={}",
                                info.session_id,
                                info.redir_flags,
                                info.no_redirect,
                                info.target_net_address,
                                info.target_fqdn,
                                info.username,
                                info.domain,
                                info.password.as_ref().map(|p| p.len()),
                                info.load_balance_info.as_ref().map(|l| l.len()),
                                info.load_balance_info
                                    .as_ref()
                                    .map(|l| l.iter().map(|b| format!("{b:02x}")).collect::<String>())
                                    .unwrap_or_default(),
                            );
                            return Err(reason);
                        }
                        if msg.contains("unexpected channel received") {
                            // #425: KRDP (FreeRDP server) addresses the IO channel
                            // as MCS channel 0 for a small one-shot control PDU
                            // (not a valid ShareControl PDU). IronRDP's session
                            // layer treats data on a non-joined channel as fatal;
                            // a robust client ignores it and continues. Dropping
                            // this frame lets the EGFX pipeline (drdynvc) proceed —
                            // without it, KRDP sessions die before the first frame.
                            debug!("Ignoring PDU on unexpected channel: {}", msg);
                        } else if msg.contains("got Server Demand Active PDU") {
                            // #438: FreeRDP's shadow server starts a mid-session
                            // Deactivation-Reactivation (it resizes the client to
                            // its display) with a bare Server Demand Active — no
                            // preceding Deactivate All — which IronRDP's session
                            // layer rejects. Re-enter the activation state machine
                            // and hand it the frame we already consumed.
                            info!("Bare Server Demand Active — running reactivation");
                            let mut cas = activation_factory.create();
                            let _ = stream_ctl.set_read_timeout(Some(std::time::Duration::from_secs(30)));
                            let res = perform_reactivation(
                                &mut tls_framed, &mut cas, Some(frame_to_process),
                                &mut active_stage, &mut image, state,
                            );
                            let _ = stream_ctl.set_read_timeout(Some(IDLE_TIMEOUT));
                            current_timeout = IDLE_TIMEOUT;
                            res?;
                        } else if msg.contains("unhandled") || msg.contains("unsupported") {
                            // Try to decode as slow-path bitmap update
                            if try_handle_slow_path_bitmap(frame_to_process, state) {
                                debug!("Decoded slow-path bitmap update");
                            } else {
                                debug!("Skipping unhandled PDU: {}", msg);
                            }
                        } else if msg.contains("NotEnoughBytes") && msg.contains("ShareControlHeader") {
                            // #422: the under-declared-totalLength case that used
                            // to land here no longer reaches it — our IronRDP fork
                            // accepts those outright from haven-pin-20260804, since
                            // the PDU was always complete and only the server's
                            // number was wrong. It had to be fixed there rather
                            // than here: VirtualBox mis-declares a PDU during
                            // *connection finalization* too, which never reaches
                            // this loop, so the connect failed before a session
                            // existed to skip anything.
                            //
                            // What still arrives here is genuine truncation — an
                            // inner PDU shorter than its own fixed part. Skip the
                            // frame and keep the session.
                            //
                            // Bounded, because "skip and carry on" and "the
                            // stream has desynchronised" look identical from
                            // here: on VirtualBox this fires once between many
                            // good frames, so the counter never climbs. If it
                            // does, the display has frozen and silence would be
                            // a worse answer than an error.
                            skipped_in_a_row += 1;
                            if skipped_in_a_row > MAX_SKIPPED_IN_A_ROW {
                                error!("{skipped_in_a_row} undecodable PDUs in a row: {msg}");
                                return Err(format!(
                                    "session error: {skipped_in_a_row} undecodable PDUs in a row \
                                     — last was {msg}"
                                ));
                            }
                            debug!("Skipping PDU with a mis-declared length: {}", msg);
                        } else {
                            // #437: a fatal protocol error is not a clean exit —
                            // surface it through on_error so the app layer marks
                            // the tab dead instead of leaving it "connected".
                            error!("Session process error: {}", msg);
                            return Err(format!("session error: {msg}"));
                        }
                    }
                }
            }
            Err(e) => {
                if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut {
                    // No data available, continue loop to process input
                    continue;
                }
                error!("Read PDU error: {:?}", e);
                break;
            }
        }
    }

    // A session that ends on a read error rather than a shutdown request never
    // sets state.shutdown, and the caller reads that flag to tell a clean exit
    // from a failure — so signal the input thread separately rather than
    // forging a shutdown it can misread (#477).
    stop_input.store(true, std::sync::atomic::Ordering::Release);
    if let Some(handle) = input_thread {
        let _ = handle.join();
    }

    Ok(None)
}

/// Per-frame cost breakdown for the RDP display path (#466).
///
/// A reporter saw updates arrive "extremely slowly" and — importantly —
/// dropping the desktop from 4K to 1080p and the server quality to minimum
/// changed nothing. That argues the cost is NOT proportional to pixels, which
/// rules out most of the obvious suspects and leaves the fixed per-frame costs:
/// the MediaCodec round-trip, the JNI hops, the draw. Nothing in this path was
/// timed, so neither we nor the reporter could say which.
///
/// Reports an average every [PERF_REPORT_FRAMES] frames at info level, so a
/// single ordinary logcat answers it.
struct FramePerf {
    frames: u64,
    read_us: u64,
    process_us: u64,
    publish_us: u64,
    /// Writes that carried input to the wire, and the events in them (#477).
    /// Written by whichever side is delivering input — the dedicated thread on
    /// a fast-path server, the session loop on a slow-path one — so the same
    /// perf line describes both. Flushes far below the offered rate means
    /// input is being batched behind something.
    input: Arc<InputCounters>,
    since: std::time::Instant,
}

const PERF_REPORT_FRAMES: u64 = 60;
const PERF_REPORT_INTERVAL: std::time::Duration = std::time::Duration::from_secs(5);

/// The TLS session shared between the session loop and the input thread (#477).
///
/// Input used to be flushed once per loop iteration, and every iteration also
/// decoded a frame. Measured against a shadow server at 1280x800, the loop
/// spent ~99% of its wall time inside `active_stage.process()`, so 60 mouse
/// moves a second reached the wire as ~3.5 bursts a second — smooth under the
/// finger, jumpy on the server.
///
/// Sharing works because **decoding never touches the socket**: `process()`
/// operates on the `DecodedImage` and the active stage, and only the short
/// response write at the end goes near the stream. So a writer contends with
/// the *read* — bounded by the read timeout, 15ms while interacting — and
/// never with the decode.
///
/// Each `read`/`write` takes the lock and drops it, so `Framed`'s multi-call
/// reads interleave with input writes at TLS-record granularity, which rustls
/// handles: it is one `ClientConnection` mutated under one mutex, never two.
#[derive(Clone)]
struct SharedTls(Arc<Mutex<rustls::StreamOwned<rustls::ClientConnection, std::net::TcpStream>>>);

impl SharedTls {
    fn new(stream: rustls::StreamOwned<rustls::ClientConnection, std::net::TcpStream>) -> Self {
        Self(Arc::new(Mutex::new(stream)))
    }

    /// Lock, recovering from poisoning. A panicking peer thread leaves the TLS
    /// state untouched (we only ever hold the guard across one read or write),
    /// so continuing beats tearing down a working session.
    fn lock(&self) -> std::sync::MutexGuard<'_, rustls::StreamOwned<rustls::ClientConnection, std::net::TcpStream>> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl std::io::Read for SharedTls {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.lock().read(buf)
    }
}

impl std::io::Write for SharedTls {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.lock().write(buf)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.lock().flush()
    }
}

/// Input delivery counters, shared so the input thread can record what it sent
/// and the session loop's perf line can report it (#477).
#[derive(Default)]
struct InputCounters {
    flushes: std::sync::atomic::AtomicU64,
    events: std::sync::atomic::AtomicU64,
}

/// Translate queued [`InputEvent`]s into fast-path events via the input
/// database, which tracks key/button state across calls.
///
/// A whole batch is accumulated into one `Vec` so a drag leaves as a single
/// PDU rather than one per position — fewer writes, and the positions stay
/// contiguous on the wire.
/// #422: `unicode_supported` is the server's INPUT_FLAG_UNICODE. A server that
/// did not advertise it silently discards `TS_UNICODE_KEYBOARD_EVENT`
/// (MS-RDPBCGR 2.2.8.1.1.3.1.1.2) — VirtualBox's VRDP advertises
/// `InputFlags(SCANCODES)` alone and does exactly that. Dropping those events
/// here instead costs nothing and makes the loss visible: it was invisible
/// before, which is why "the keyboard does nothing" took so long to place.
fn fastpath_events_for(
    db: &mut ironrdp_input::Database,
    pending: Vec<InputEvent>,
    unicode_supported: bool,
    unicode_dropped: &std::sync::atomic::AtomicU64,
) -> Vec<ironrdp_pdu::input::fast_path::FastPathInputEvent> {
    let mut out = Vec::new();
    for event in pending {
        if matches!(event, InputEvent::UnicodeKey { .. }) && !unicode_supported {
            use std::sync::atomic::Ordering;
            let n = unicode_dropped.fetch_add(1, Ordering::Relaxed) + 1;
            if n == 1 || n % 50 == 0 {
                warn!(
                    "Dropped {n} unicode key event(s): this server did not advertise \
                     INPUT_FLAG_UNICODE, so it would discard them anyway. ASCII typing \
                     uses scancodes and is unaffected; non-ASCII characters cannot be \
                     delivered to this server (#422)."
                );
            }
            continue;
        }
        let ops = match event {
            InputEvent::Key { scancode, pressed } => {
                let sc = ironrdp_input::Scancode::from_u16(scancode);
                db.apply(std::iter::once(if pressed {
                    ironrdp_input::Operation::KeyPressed(sc)
                } else {
                    ironrdp_input::Operation::KeyReleased(sc)
                }))
            }
            InputEvent::UnicodeKey { ch, pressed } => match char::from_u32(ch) {
                Some(c) => db.apply(std::iter::once(if pressed {
                    ironrdp_input::Operation::UnicodeKeyPressed(c)
                } else {
                    ironrdp_input::Operation::UnicodeKeyReleased(c)
                })),
                None => smallvec::SmallVec::new(),
            },
            InputEvent::MouseMove { x, y } => db.apply(std::iter::once(
                ironrdp_input::Operation::MouseMove(ironrdp_input::MousePosition { x, y }),
            )),
            InputEvent::MouseButton { button, pressed } => {
                let btn = match button {
                    MouseButton::Left => ironrdp_input::MouseButton::Left,
                    MouseButton::Right => ironrdp_input::MouseButton::Right,
                    MouseButton::Middle => ironrdp_input::MouseButton::Middle,
                };
                db.apply(std::iter::once(if pressed {
                    ironrdp_input::Operation::MouseButtonPressed(btn)
                } else {
                    ironrdp_input::Operation::MouseButtonReleased(btn)
                }))
            }
            InputEvent::MouseWheel { vertical: _, delta } => {
                db.apply(std::iter::once(ironrdp_input::Operation::WheelRotations(
                    ironrdp_input::WheelRotations {
                        is_vertical: true,
                        rotation_units: delta as i16,
                    },
                )))
            }
            // Clipboard travels on the CLIPRDR channel, not the input path.
            InputEvent::ClipboardText(_) => smallvec::SmallVec::new(),
        };
        out.extend(ops);
    }
    out
}

/// How many extra frames reactivation will pull in to complete a PDU that
/// arrived split (#422). The observed case needs exactly one; the bound keeps
/// a real desync from consuming the stream frame by frame.
const REACTIVATION_MAX_JOINS: u32 = 4;

impl FramePerf {
    fn new(input: Arc<InputCounters>) -> Self {
        Self {
            frames: 0,
            read_us: 0,
            process_us: 0,
            publish_us: 0,
            input,
            since: std::time::Instant::now(),
        }
    }

    fn maybe_report(&mut self) {
        // Frame count alone never fired on the EGFX path — surface updates
        // publish through the egfx module rather than
        // ActiveStageOutput::GraphicsUpdate, so `frames` stayed at 0 on exactly
        // the sessions worth profiling. Report on elapsed time as well, and
        // call this once per loop iteration so input-only activity (#477) is
        // visible even when no frame arrives.
        use std::sync::atomic::Ordering;
        let elapsed = self.since.elapsed();
        let idle = self.frames == 0 && self.input.events.load(Ordering::Relaxed) == 0;
        if idle || (self.frames < PERF_REPORT_FRAMES && elapsed < PERF_REPORT_INTERVAL) {
            return;
        }
        let input_flushes = self.input.flushes.swap(0, Ordering::Relaxed);
        let input_events = self.input.events.swap(0, Ordering::Relaxed);
        let secs = elapsed.as_secs_f64().max(0.000_001);
        let frames = self.frames;
        let n = self.frames.max(1);
        info!(
            "RDP perf: {:.1} fps over {frames} frames — per frame: read {}us, process {}us, publish {}us (read = blocked on server, so a large read means the server is the limit, not us); input {:.1} flushes/s, {:.1} events/s (#477: flushes/s tracking fps means input is stuck behind decode)",
            frames as f64 / secs,
            self.read_us / n,
            self.process_us / n,
            self.publish_us / n,
            input_flushes as f64 / secs,
            input_events as f64 / secs,
        );
        *self = Self::new(Arc::clone(&self.input));
    }
}

/// Try to decode a slow-path bitmap update from the raw X224 frame
/// and blit it directly into our ARGB framebuffer.
fn try_handle_slow_path_bitmap(
    frame: &[u8],
    state: &Arc<RwLock<SessionState>>,
) -> bool {
    use ironrdp_pdu::{Decode, cursor::ReadCursor};
    use ironrdp_pdu::bitmap::BitmapUpdateData;

    // The ShareDataPdu::Update stores raw bitmap bytes. Scan for the
    // UPDATETYPE_BITMAP marker (0x0001 LE) in the frame.
    // Decode the X224/MCS/ShareControl/ShareData headers to extract the
    // Update PDU payload.
    // These moved out of ironrdp-connector's `legacy` module, which was
    // deleted in connector 0.10.0, into the PDU crate that always owned
    // the wire formats.
    use ironrdp_pdu::mcs::decode_send_data_indication;
    use ironrdp_pdu::rdp::headers::{decode_io_channel, IoChannelPdu};
    use ironrdp_pdu::rdp::headers::ShareDataPdu;

    let ctx = match decode_send_data_indication(frame) {
        Ok(c) => c,
        Err(_) => return false,
    };
    let io_pdu = match decode_io_channel(ctx) {
        Ok(p) => p,
        Err(_) => return false,
    };
    let update_bytes = match io_pdu {
        IoChannelPdu::Data(data_ctx) => match data_ctx.pdu {
            ShareDataPdu::Update(bytes) => bytes,
            _ => return false,
        },
        _ => return false,
    };

    debug!("Update PDU payload: {} bytes", update_bytes.len());

    // If EGFX_PDU_DUMP_DIR is set, capture the legacy slow-path
    // BitmapUpdateData payload too — the BitmapUpdate type is the
    // *other* RDPGFX_RECT16 consumer affected by IronRDP PR #1238
    // (exclusive vs inclusive rectangles), and having a real
    // Server 2025 capture of one helps validate the type flip.
    if let Ok(dir) = std::env::var("EGFX_PDU_DUMP_DIR") {
        // Counter is process-local and best-effort; we just want
        // unique-enough filenames per dump session.
        use std::sync::atomic::{AtomicU64, Ordering};
        static N: AtomicU64 = AtomicU64::new(0);
        let n = N.fetch_add(1, Ordering::Relaxed);
        let path = format!("{dir}/slow_path_bitmap_update_{n:04}.bin");
        if let Err(e) = std::fs::write(&path, &update_bytes) {
            warn!("EGFX_PDU_DUMP write failed for {path}: {e}");
        }
    }

    let mut cursor = ReadCursor::new(&update_bytes);
    let bitmap_update = match BitmapUpdateData::decode(&mut cursor) {
        Ok(u) => u,
        Err(e) => {
            debug!("BitmapUpdateData decode failed: {:?}", e);
            return false;
        }
    };

    debug!("Slow-path bitmap: {} rectangles", bitmap_update.rectangles.len());

    // Get framebuffer dimensions
    let (fb_width, fb_height) = {
        let s = match state.read() {
            Ok(s) => s,
            Err(_) => return false,
        };
        match &s.framebuffer {
            Some(fb) => (fb.width as usize, fb.height as usize),
            None => return false,
        }
    };

    let mut any_updates = false;

    for update in &bitmap_update.rectangles {
        let w = update.width as usize;
        let h = update.height as usize;
        let bpp = update.bits_per_pixel;

        // Decode bitmap data to raw pixels
        let is_compressed = update.compression_flags.contains(
            ironrdp_pdu::bitmap::Compression::BITMAP_COMPRESSION
        );
        let has_hdr = update.compressed_data_header.is_some();
        debug!("  rect {}x{} at ({},{}) bpp={} compressed={} rdp6_hdr={} data_len={}",
            w, h, update.rectangle.left, update.rectangle.top,
            bpp, is_compressed, has_hdr, update.bitmap_data.len());

        let mut decoded_rgb = Vec::new();
        let pixel_data: Option<(&[u8], u16, bool)>; // (data, bpp, flip)

        if is_compressed {
            if bpp == 32 && has_hdr {
                // RDP6 Bitmap Compressed Stream (has CompressedDataHeader)
                let mut decoder = ironrdp_graphics::rdp6::BitmapStreamDecoder::default();
                if decoder.decode_bitmap_stream_to_rgb24(
                    update.bitmap_data, &mut decoded_rgb, w, h
                ).is_ok() {
                    pixel_data = Some((&decoded_rgb, 24, true));
                } else {
                    continue;
                }
            } else if bpp == 32 {
                // xrdp sends 32bpp as 24bpp interleaved RLE (3 bytes BGR per pixel)
                if ironrdp_graphics::rle::decompress_24_bpp(
                    update.bitmap_data, &mut decoded_rgb, w, h
                ).is_ok() {
                    pixel_data = Some((&decoded_rgb, 24, true));
                } else {
                    debug!("  32bpp RLE decompress failed, data_len={}", update.bitmap_data.len());
                    continue;
                }
            } else {
                // Interleaved RLE compression for <32bpp
                if ironrdp_graphics::rle::decompress(
                    update.bitmap_data, &mut decoded_rgb, w, h, bpp as usize
                ).is_ok() {
                    pixel_data = Some((&decoded_rgb, bpp, true));
                } else {
                    continue;
                }
            }
        } else {
            pixel_data = Some((update.bitmap_data, bpp, true));
        }

        let (pixels, effective_bpp, flip) = match pixel_data {
            Some(p) => p,
            None => continue,
        };

        // Blit into ARGB framebuffer
        let rect = &update.rectangle;
        let dst_x = rect.left as usize;
        let dst_y = rect.top as usize;

        if let Ok(mut s) = state.write() {
            if let Some(ref mut fb) = s.framebuffer {
                let fb_data = &mut fb.pixels;

                for row in 0..h {
                    let src_row = if flip { h - 1 - row } else { row };
                    let dst_row_y = dst_y + row;
                    if dst_row_y >= fb_height { break; }

                    for col in 0..w {
                        let dst_col_x = dst_x + col;
                        if dst_col_x >= fb_width { break; }

                        // Read source pixel
                        let (r, g, b) = match effective_bpp {
                            24 => {
                                let si = (src_row * w + col) * 3;
                                if si + 2 >= pixels.len() { continue; }
                                // RLE 24bpp output is BGR (LE u24): [B, G, R]
                                let b_val = pixels[si];
                                let g_val = pixels[si + 1];
                                let r_val = pixels[si + 2];
                                (r_val, g_val, b_val)
                            }
                            16 => {
                                let si = (src_row * w + col) * 2;
                                if si + 1 >= pixels.len() { continue; }
                                let val = u16::from_le_bytes([pixels[si], pixels[si + 1]]);
                                let r5 = ((val >> 11) & 0x1F) as u8;
                                let g6 = ((val >> 5) & 0x3F) as u8;
                                let b5 = (val & 0x1F) as u8;
                                ((r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2))
                            }
                            32 => {
                                let si = (src_row * w + col) * 4;
                                if si + 3 >= pixels.len() { continue; }
                                // BGRX format
                                (pixels[si + 2], pixels[si + 1], pixels[si])
                            }
                            _ => continue,
                        };

                        // Android ARGB_8888 copyPixelsFromBuffer wants RGBA byte
                        // order: [R, G, B, A] (#212 — writing [B,G,R,A] swapped
                        // red/blue on-device, e.g. xrdp's blue login background
                        // rendered brown).
                        let di = (dst_row_y * fb_width + dst_col_x) * 4;
                        if di + 3 < fb_data.len() {
                            fb_data[di] = r;
                            fb_data[di + 1] = g;
                            fb_data[di + 2] = b;
                            fb_data[di + 3] = 0xFF;
                        }
                    }
                }

                // Verify a pixel was written
                let check_di = (dst_y * fb_width + dst_x) * 4;
                if check_di + 3 < fb_data.len() {
                    debug!("  Written pixel at ({},{}) = [{:02x},{:02x},{:02x},{:02x}]",
                        dst_x, dst_y, fb_data[check_di], fb_data[check_di+1],
                        fb_data[check_di+2], fb_data[check_di+3]);
                }

                // Track dirty rect
                s.dirty_rects.push(RdpRect {
                    x: rect.left,
                    y: rect.top,
                    width: w as u16,
                    height: h as u16,
                });

                any_updates = true;
            }
        }
    }

    // Log a sample pixel for color debugging
    if any_updates {
        if let Ok(s) = state.read() {
            if let Some(ref fb) = s.framebuffer {
                // Sample pixel near center
                let cx = fb_width / 2;
                let cy = fb_height / 2;
                let pi = (cy * fb_width + cx) * 4;
                if pi + 3 < fb.pixels.len() {
                    debug!("Sample pixel ({},{}) ARGB: [{:02x},{:02x},{:02x},{:02x}]",
                        cx, cy, fb.pixels[pi], fb.pixels[pi+1], fb.pixels[pi+2], fb.pixels[pi+3]);
                }
            }
        }
    }

    // Notify callback outside the lock
    if any_updates {
        let cb = state.read().ok().and_then(|s| s.frame_callback.clone());
        if let Some(cb) = cb {
            cb.on_frame_update(0, 0, fb_width as u16, fb_height as u16);
        }
    }

    any_updates
}

/// Drive a server-initiated Deactivation-Reactivation Sequence (MS-RDPBCGR
/// 1.3.1.3) to completion on the live transport, then swap the session onto
/// the renegotiated parameters (#438). Servers reactivate to change session
/// parameters mid-session — FreeRDP's shadow server does it to resize the
/// client to its display, and Windows does it on resolution changes.
///
/// `first_frame` carries the already-consumed `Server Demand Active` for
/// servers that skip the preceding `Deactivate All` PDU (FreeRDP shadow);
/// `None` when the sequence was entered properly via `Deactivate All`.
///
/// The caller must widen the transport read timeout around this call — the
/// sequence blocks on multi-PDU reads that the session loop's 100ms poll
/// timeout would abort.
/// Render an error together with its source chain.
///
/// `ConnectorError`'s Display prints only its own context — "decode error" —
/// while the part that actually explains the failure sits one level down. A
/// server dropping the link reports `received disconnect provider ultimatum`
/// in the inner `DecodeError`, and printing only the outer context turns that
/// into a bare "decode error" with nothing to act on.
///
/// IronRDP 0.10/0.11 routes more failures through the generic decode variant
/// than 0.9 did — 0.9 raised the ultimatum as its own reason — so without this
/// the upgrade would have made a whole class of disconnects unreadable.
fn error_chain(e: &(dyn std::error::Error + 'static)) -> String {
    let mut out = strip_location(&e.to_string());
    let mut source = e.source();
    while let Some(inner) = source {
        let msg = strip_location(&inner.to_string());
        // Skip a link that only repeats what the outer message already said.
        if !out.contains(&msg) {
            out.push_str(": ");
            out.push_str(&msg);
        }
        source = inner.source();
    }
    out
}

/// Drop IronRDP's leading `[context @ /path/to/file.rs:12]` marker.
///
/// Those paths point into the Rust toolchain and the cargo registry, so they
/// say nothing to a user looking at a failed connection and crowd out the part
/// that does. Only a marker at the very start is removed, so a message that
/// happens to contain brackets later keeps them.
fn strip_location(msg: &str) -> String {
    let trimmed = msg.trim_start();
    if !trimmed.starts_with('[') {
        return trimmed.to_owned();
    }
    match trimmed.find(']') {
        // Only strip when it really is a location marker.
        Some(end) if trimmed[..end].contains(" @ ") => trimmed[end + 1..].trim_start().to_owned(),
        _ => trimmed.to_owned(),
    }
}

fn perform_reactivation<S: std::io::Read + std::io::Write>(
    framed: &mut ironrdp_blocking::Framed<S>,
    cas: &mut ironrdp_connector::connection_activation::ConnectionActivationSequence,
    first_frame: Option<&[u8]>,
    active_stage: &mut ironrdp_session::ActiveStage,
    image: &mut ironrdp_session::image::DecodedImage,
    state: &Arc<RwLock<SessionState>>,
) -> Result<(), String> {
    use ironrdp_connector::connection_activation::ConnectionActivationState;
    use ironrdp_connector::Sequence as _;
    use ironrdp_graphics::image_processing::PixelFormat;

    let mut buf = ironrdp_core::WriteBuf::new();
    let feed = |cas: &mut ironrdp_connector::connection_activation::ConnectionActivationSequence,
                    input: &[u8],
                    framed: &mut ironrdp_blocking::Framed<S>,
                    buf: &mut ironrdp_core::WriteBuf|
     -> Result<(), String> {
        buf.clear();
        let written = cas
            .step(input, buf)
            .map_err(|e| format!("reactivation step: {}", error_chain(&e)))?;
        if let Some(n) = written.size() {
            framed
                .write_all(&buf[..n])
                .map_err(|e| format!("reactivation write: {e}"))?;
        }
        Ok(())
    };

    if let Some(frame) = first_frame {
        // The frame that announced the reactivation can be only part of its
        // PDU. The loop below reads by hint and so always gets a whole one,
        // but this first frame was already taken off the wire by the caller.
        //
        // CAUTION (#422): this join was added on the reading that
        // `NotEnoughBytes { received: 24, expected: 4287 }` meant a PDU split
        // across two frames. That reading is wrong for the session loop —
        // `received`/`expected` are the declared totalLength and the decoded
        // size, so nothing is missing (see
        // under_declared_share_control_length_is_not_a_truncated_pdu), and the
        // matching in-session reassembly has been removed.
        //
        // It is left here because neither reporter log shows reactivation
        // running at all, so there is no evidence about which branch fires on
        // a Demand Active, and ripping it out on theory alone would risk the
        // crash d312d2bf fixed. Bounded by REACTIVATION_MAX_JOINS. If a log
        // ever shows "Reactivation: joining", check `expected` against the
        // frame length first: if it equals frame - 15, it is the same
        // under-declared quirk and this loop is chasing nothing.
        let mut pending = frame.to_vec();
        let mut joins = 0;
        loop {
            match feed(cas, &pending, framed, &mut buf) {
                Ok(()) => break,
                Err(e) if e.contains("NotEnoughBytes") && joins < REACTIVATION_MAX_JOINS => {
                    joins += 1;
                    let (_, more) = framed
                        .read_pdu()
                        .map_err(|io| format!("reactivation read (join {joins}): {io}"))?;
                    debug!(
                        "Reactivation: joining {} + {} byte fragments (#422)",
                        pending.len(),
                        more.len()
                    );
                    pending.extend_from_slice(&more);
                }
                // Bounded on purpose: a genuine desync fails fast rather than
                // swallowing the whole stream one frame at a time.
                Err(e) => return Err(e),
            }
        }
    }
    while !cas.state().is_terminal() {
        // A hint means "read that PDU and feed it"; no hint means the next
        // step emits a client-side PDU and takes no input (the finalization
        // Synchronize/Control/FontList sends) — same contract as
        // ironrdp_blocking::single_sequence_step.
        let pdu = match cas.next_pdu_hint() {
            Some(hint) => Some(
                framed
                    .read_by_hint(hint)
                    .map_err(|e| format!("reactivation read: {e}"))?,
            ),
            None => None,
        };
        feed(cas, pdu.as_deref().unwrap_or(&[]), framed, &mut buf)?;
    }

    // connector 0.10.0 dropped these from the Finalized variant; the sequence
    // still knows them, and they cannot change across a reactivation.
    let io_channel_id = cas.io_channel_id();
    let user_channel_id = cas.user_channel_id();

    match cas.connection_activation_state() {
        ConnectionActivationState::Finalized {
            desktop_size,
            share_id,
            // #422: a reactivation could in principle renegotiate input flags,
            // but no known server flips fast-path support mid-session; the
            // connect-time decision stands.
            input_flags: _,
            enable_server_pointer,
            pointer_software_rendering,
            // refresh_rect_support / suppress_output_support and anything
            // upstream adds next: reactivation reuses the connect-time
            // decisions, so nothing here consumes them.
            ..
        } => {
            info!(
                "Reactivation finalized: desktop {}x{}",
                desktop_size.width, desktop_size.height
            );
            *image = ironrdp_session::image::DecodedImage::new(
                PixelFormat::RgbA32,
                desktop_size.width,
                desktop_size.height,
            );
            active_stage.set_fastpath_processor(
                ironrdp_session::fast_path::ProcessorBuilder {
                    io_channel_id,
                    user_channel_id,
                    share_id,
                    enable_server_pointer,
                    pointer_software_rendering,
                    // `bulk_decompressor` is gone: the processor always owns one
                    // now (Devolutions/IronRDP#1255), which also means a
                    // reactivation no longer drops the decompression history a
                    // later compressed update refers back to.
                }
                .build(),
            );
            active_stage.set_share_id(share_id);
            active_stage.set_enable_server_pointer(enable_server_pointer);

            let resize_cb = {
                let mut s = state
                    .write()
                    .map_err(|_| "session state lock poisoned".to_string())?;
                s.framebuffer = Some(FrameData {
                    width: desktop_size.width,
                    height: desktop_size.height,
                    pixels: vec![0u8; desktop_size.width as usize * desktop_size.height as usize * 4],
                });
                s.frame_callback.clone()
            };
            // Outside the lock — Kotlin handlers may call back into the client.
            if let Some(cb) = resize_cb {
                cb.on_resize(desktop_size.width, desktop_size.height);
            }
            Ok(())
        }
        other => Err(format!(
            "reactivation ended in unexpected state: {other:?}"
        )),
    }
}

/// Copy updated region from DecodedImage to our ARGB framebuffer
/// and notify callbacks.
fn update_framebuffer(
    state: &Arc<RwLock<SessionState>>,
    image: &ironrdp_session::image::DecodedImage,
    rect: &ironrdp_pdu::geometry::InclusiveRectangle,
) {
    use ironrdp_pdu::geometry::Rectangle;

    let fb_width = image.width() as usize;
    let fb_height = image.height() as usize;
    let pixel_data = image.data();

    // ironrdp's DecodedImage is PixelFormat::RgbA32 = [R,G,B,A] in memory.
    // Android Bitmap.Config.ARGB_8888 + copyPixelsFromBuffer also expects RGBA
    // byte order ([R,G,B,A]) — confirmed on-device (#212): a frame written as
    // [B,G,R,A] renders with red/blue swapped (the blue Windows accent showed
    // orange). So a verbatim copy is correct here; no swap.
    let pixel_count = fb_width * fb_height;
    let needed = pixel_count * 4;

    let rdp_rect = RdpRect {
        x: rect.left,
        y: rect.top,
        width: rect.width(),
        height: rect.height(),
    };

    let frame_cb = {
        let mut s = match state.write() {
            Ok(s) => s,
            Err(_) => return,
        };
        // Copy ONLY the rows the update touched, into the buffer we already
        // own. This used to be `pixel_data[..needed].to_vec()` — a fresh
        // whole-framebuffer allocation per update, regardless of how little
        // the update changed.
        //
        // #422: a reporter's 38-second VirtualBox session logged 4305 graphics
        // updates whose rectangles lay OUTSIDE the 1920x1080 image (the server
        // was painting a larger desktop), so ironrdp skipped them — they drew
        // nothing at all. Each still cost an 8.3MB allocate-copy-free here:
        // ~36GB of memcpy and 4305 heap churns for zero visible change, on a
        // phone. Clamping to the intersection makes those cost nothing, and an
        // ordinary small update cost its own area instead of the whole screen.
        let reusable = matches!(
            &s.framebuffer,
            Some(f) if f.width as usize == fb_width
                && f.height as usize == fb_height
                && f.pixels.len() == needed
        );
        if reusable {
            // Inclusive rectangle, clamped to the image — an out-of-bounds
            // update yields an empty range and copies nothing, which is
            // correct: those pixels do not exist in this framebuffer.
            let x0 = (rect.left as usize).min(fb_width);
            let x1 = (rect.right as usize + 1).min(fb_width);
            let y0 = (rect.top as usize).min(fb_height);
            let y1 = (rect.bottom as usize + 1).min(fb_height);
            if x1 > x0 {
                let fb = match s.framebuffer.as_mut() {
                    Some(f) => f,
                    None => return,
                };
                for y in y0..y1 {
                    let start = (y * fb_width + x0) * 4;
                    let end = (y * fb_width + x1) * 4;
                    fb.pixels[start..end].copy_from_slice(&pixel_data[start..end]);
                }
            }
        } else {
            // First frame, or the image was resized — take the whole thing once.
            s.framebuffer = Some(FrameData {
                width: fb_width as u16,
                height: fb_height as u16,
                pixels: pixel_data[..needed].to_vec(),
            });
        }
        s.dirty_rects.push(rdp_rect.clone());
        s.frame_callback.clone()
    };
    // Invoke callback outside the lock — Kotlin's onFrameUpdate calls
    // getFramebuffer() which needs a read lock.
    if let Some(cb) = frame_cb {
        cb.on_frame_update(rdp_rect.x, rdp_rect.y, rdp_rect.width, rdp_rect.height);
    }
}

/// Minimal RFC 1928 SOCKS5 CONNECT client. Vendored inline (~50 lines)
/// rather than pulling another crate — IronRDP's only need is "dial
/// `target_host:target_port` through `proxy_host:proxy_port`". No-auth
/// only; that matches what wgbridge / tsbridge serve on the other side.
fn socks5_connect(
    proxy_host: &str,
    proxy_port: u16,
    target_host: &str,
    target_port: u16,
) -> std::io::Result<TcpStream> {
    use std::io::{Error, ErrorKind, Read, Write};

    let mut stream = TcpStream::connect(format!("{}:{}", proxy_host, proxy_port))?;

    // METHOD-NEG: ver=5, nmethods=1, methods=[0x00 no-auth]
    stream.write_all(&[0x05, 0x01, 0x00])?;
    let mut method_reply = [0u8; 2];
    stream.read_exact(&mut method_reply)?;
    if method_reply[0] != 0x05 || method_reply[1] != 0x00 {
        return Err(Error::new(
            ErrorKind::Other,
            format!("SOCKS5 method negotiation failed: {:?}", method_reply),
        ));
    }

    // CONNECT: ver=5, cmd=1 (CONNECT), rsv=0, atyp=3 (DOMAIN), len, name, port BE
    let host_bytes = target_host.as_bytes();
    if host_bytes.len() > 255 {
        return Err(Error::new(ErrorKind::InvalidInput, "host longer than 255 bytes"));
    }
    let mut req = Vec::with_capacity(5 + host_bytes.len() + 2);
    req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    req.extend_from_slice(host_bytes);
    req.extend_from_slice(&target_port.to_be_bytes());
    stream.write_all(&req)?;

    // Reply: ver, rep, rsv, atyp, BND.ADDR (variable), BND.PORT (2)
    let mut reply_hdr = [0u8; 4];
    stream.read_exact(&mut reply_hdr)?;
    if reply_hdr[0] != 0x05 {
        return Err(Error::new(ErrorKind::Other, "SOCKS5 reply: not version 5"));
    }
    if reply_hdr[1] != 0x00 {
        return Err(Error::new(
            ErrorKind::Other,
            format!("SOCKS5 CONNECT failed: REP=0x{:02x}", reply_hdr[1]),
        ));
    }
    let bnd_len: usize = match reply_hdr[3] {
        0x01 => 4,  // IPv4
        0x04 => 16, // IPv6
        0x03 => {
            let mut name_len = [0u8; 1];
            stream.read_exact(&mut name_len)?;
            name_len[0] as usize
        }
        atyp => {
            return Err(Error::new(
                ErrorKind::Other,
                format!("SOCKS5: unsupported BND atyp 0x{:02x}", atyp),
            ));
        }
    };
    let mut bnd_skip = vec![0u8; bnd_len + 2]; // +2 for BND.PORT
    stream.read_exact(&mut bnd_skip)?;

    Ok(stream)
}

#[cfg(test)]
mod codec_advertisement_tests {
    use super::{build_config, RdpConfig};
    use ironrdp_pdu::rdp::capability_sets::CodecProperty;

    fn config() -> RdpConfig {
        RdpConfig {
            username: String::new(),
            password: String::new(),
            domain: String::new(),
            width: 1920,
            height: 1080,
            color_depth: 32,
            enable_credssp: false,
            pinned_cert_sha256: None,
            progressive_upgrade: false,
            avc_enabled: true,
            keyboard_layout: 0,
        }
    }

    /// #461: a Windows server took up NSCodec because we advertised it, then
    /// sent fast-path surface bits with codec id 1 — which ironrdp-session
    /// rejects outright, killing the session mid-logon with
    /// `Fast-Path: unexpected codec ID: 1`. Never advertise a codec the
    /// surface-bits path cannot decode; the server will happily use it.
    #[test]
    fn nscodec_is_not_advertised() {
        let cfg = build_config(&config());
        let codecs = &cfg.bitmap.as_ref().expect("bitmap config").codecs.0;
        assert!(
            !codecs
                .iter()
                .any(|c| matches!(c.property, CodecProperty::NsCodec(_))),
            "NSCodec must not be advertised: ironrdp-session's CodecId::from_u8 \
             accepts only NONE(0), REMOTEFX(3), QOI(0x0A/0x0B), so a server that \
             takes it up kills the session",
        );
    }

    /// The advertisement must not become empty either — RemoteFX is what makes
    /// Windows send efficient tile updates rather than 16bpp line-by-line RLE.
    #[test]
    fn remotefx_is_still_advertised() {
        let cfg = build_config(&config());
        let codecs = &cfg.bitmap.as_ref().expect("bitmap config").codecs.0;
        assert!(
            codecs
                .iter()
                .any(|c| matches!(c.property, CodecProperty::RemoteFx(_))),
            "RemoteFX must stay advertised",
        );
    }
}

#[cfg(test)]
mod region_tests {
    use super::{FrameData, RdpClient, RdpConfig};

    /// #422: the region fetch replaces an 8.29 MB full-framebuffer copy per
    /// update, so it has to extract exactly the right rows — an off-by-one in
    /// the stride would show as a smeared or shifted patch on screen.
    fn client_with_framebuffer(w: u16, h: u16) -> RdpClient {
        let client = RdpClient::new(RdpConfig {
            username: String::new(),
            password: String::new(),
            domain: String::new(),
            width: w,
            height: h,
            color_depth: 32,
            enable_credssp: false,
            pinned_cert_sha256: None,
            progressive_upgrade: false,
            avc_enabled: false,
            keyboard_layout: 0,
        });
        // Distinct value per pixel so a wrong row or column is detectable.
        let mut pixels = vec![0u8; w as usize * h as usize * 4];
        for y in 0..h as usize {
            for x in 0..w as usize {
                let i = (y * w as usize + x) * 4;
                pixels[i] = x as u8;
                pixels[i + 1] = y as u8;
                pixels[i + 2] = 0x5A;
                pixels[i + 3] = 0xFF;
            }
        }
        client.state.write().unwrap().framebuffer = Some(FrameData { width: w, height: h, pixels });
        client
    }

    #[test]
    fn region_extracts_the_requested_rows_and_columns() {
        let client = client_with_framebuffer(64, 32);
        let region = client.get_framebuffer_region(10, 4, 3, 2).expect("region");
        assert_eq!((region.width, region.height), (3, 2));
        assert_eq!(region.pixels.len(), 3 * 2 * 4);
        // Row 0 of the region is framebuffer row 4, columns 10..13.
        assert_eq!(&region.pixels[0..4], &[10, 4, 0x5A, 0xFF]);
        assert_eq!(&region.pixels[4..8], &[11, 4, 0x5A, 0xFF]);
        assert_eq!(&region.pixels[8..12], &[12, 4, 0x5A, 0xFF]);
        // Row 1 is framebuffer row 5, same columns — proves the stride step.
        assert_eq!(&region.pixels[12..16], &[10, 5, 0x5A, 0xFF]);
    }

    #[test]
    fn region_is_clipped_to_the_framebuffer() {
        let client = client_with_framebuffer(64, 32);
        let region = client.get_framebuffer_region(60, 30, 100, 100).expect("clipped region");
        assert_eq!((region.width, region.height), (4, 2));
        assert_eq!(region.pixels.len(), 4 * 2 * 4);
    }

    #[test]
    fn region_outside_or_empty_returns_none() {
        let client = client_with_framebuffer(64, 32);
        assert!(client.get_framebuffer_region(64, 0, 4, 4).is_none(), "x at the edge");
        assert!(client.get_framebuffer_region(0, 32, 4, 4).is_none(), "y at the edge");
        assert!(client.get_framebuffer_region(0, 0, 0, 4).is_none(), "zero width");
    }

    #[test]
    fn region_without_a_framebuffer_returns_none() {
        let client = RdpClient::new(RdpConfig {
            username: String::new(),
            password: String::new(),
            domain: String::new(),
            width: 8,
            height: 8,
            color_depth: 32,
            enable_credssp: false,
            pinned_cert_sha256: None,
            progressive_upgrade: false,
            avc_enabled: false,
            keyboard_layout: 0,
        });
        assert!(client.get_framebuffer_region(0, 0, 4, 4).is_none());
    }
}

#[cfg(test)]
mod error_chain_tests {
    use super::{error_chain, strip_location};

    #[derive(Debug)]
    struct Layer {
        msg: String,
        source: Option<Box<Layer>>,
    }

    impl std::fmt::Display for Layer {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str(&self.msg)
        }
    }

    impl std::error::Error for Layer {
        fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
            self.source.as_deref().map(|e| e as &(dyn std::error::Error + 'static))
        }
    }

    fn layer(msg: &str, source: Option<Layer>) -> Layer {
        Layer { msg: msg.to_owned(), source: source.map(Box::new) }
    }

    #[test]
    fn the_reason_one_level_down_is_reported() {
        // The case this exists for: IronRDP 0.10 reports a server dropping the
        // link as a bare "decode error", with the reason in the source.
        let e = layer(
            "[decode error @ /rustc/abc/library/core/src/ops/function.rs:250] decode error",
            Some(layer(
                "[decode_send_data_indication @ /home/x/.cargo/registry/ironrdp-core-0.2.1/src/error.rs:248] other (received disconnect provider ultimatum)",
                None,
            )),
        );
        assert_eq!(
            "decode error: other (received disconnect provider ultimatum)",
            error_chain(&e),
        );
    }

    #[test]
    fn a_repeated_link_is_not_appended_twice() {
        let e = layer("decode error", Some(layer("decode error", None)));
        assert_eq!("decode error", error_chain(&e));
    }

    #[test]
    fn a_chain_deeper_than_two_is_followed() {
        let e = layer("a", Some(layer("b", Some(layer("c", None)))));
        assert_eq!("a: b: c", error_chain(&e));
    }

    #[test]
    fn an_error_with_no_source_is_unchanged() {
        assert_eq!("plain failure", error_chain(&layer("plain failure", None)));
    }

    #[test]
    fn location_markers_are_stripped() {
        assert_eq!("decode error", strip_location("[decode error @ /rustc/abc/f.rs:250] decode error"));
        assert_eq!("other (x)", strip_location("[f @ /path/error.rs:248] other (x)"));
    }

    #[test]
    fn text_that_merely_starts_with_a_bracket_is_left_alone() {
        // Only a real `[context @ path]` marker is a location; a message that
        // opens with a bracket for its own reasons keeps it.
        assert_eq!("[not a location] body", strip_location("[not a location] body"));
        assert_eq!("[unclosed bracket", strip_location("[unclosed bracket"));
        assert_eq!("no brackets at all", strip_location("no brackets at all"));
    }
}

#[cfg(test)]
mod tests {
    use super::classify_raw_finalize;

    /// The wire-format string that surfaced as "TLS handshake failed"
    /// on the Pixel against winserver2025 + colorDepth=16 (v5.24.69).
    /// This is a post-handshake socket close — TLS and CredSSP both
    /// succeeded in the trace; the server TCP-FIN'd during MCS Connect.
    /// Old wrapper labelled it "TLS handshake failed:". New wrapper
    /// must point at the real cause (server hung up; check colour depth).
    #[test]
    fn unexpected_eof_after_credssp_no_longer_labelled_as_tls_handshake() {
        let raw = r#"Error { context: "read frame by hint", kind: Custom, source: Some(Custom { kind: UnexpectedEof, error: "peer closed connection without sending TLS close_notify: https://docs.rs/rustls/latest/rustls/manual/_03_howto/index.html#unexpected-eof" }) }"#;
        let out = classify_raw_finalize(raw);
        assert!(
            !out.starts_with("TLS handshake failed"),
            "regression: still labelling post-handshake close as TLS handshake — {out}"
        );
        assert!(
            out.starts_with("Server closed the connection during RDP setup"),
            "expected server-closed framing, got: {out}"
        );
        assert!(
            out.contains("colour depth"),
            "should hint at colour depth fix, got: {out}"
        );
    }

    /// #422: a server that never advertised INPUT_FLAG_UNICODE discards
    /// TS_UNICODE_KEYBOARD_EVENT silently (MS-RDPBCGR 2.2.8.1.1.3.1.1.2).
    /// VirtualBox's VRDP advertises `InputFlags(SCANCODES)` alone and does
    /// exactly that — verified against VBox 7.2.6 with a Windows 11 guest,
    /// where scancodes drive the guest and unicode events change nothing.
    /// Drop them here so the loss is counted and logged rather than invisible.
    #[test]
    fn unicode_keys_are_dropped_when_the_server_cannot_take_them() {
        use super::{fastpath_events_for, InputEvent};
        use std::sync::atomic::{AtomicU64, Ordering};

        let dropped = AtomicU64::new(0);
        let mut db = ironrdp_input::Database::new();
        let events = vec![
            InputEvent::UnicodeKey { ch: 'e' as u32, pressed: true },
            InputEvent::UnicodeKey { ch: 'e' as u32, pressed: false },
        ];
        let out = fastpath_events_for(&mut db, events, false, &dropped);
        assert!(out.is_empty(), "nothing should reach the wire");
        assert_eq!(2, dropped.load(Ordering::Relaxed), "both drops counted");
    }

    /// The same events on a server that *did* advertise unicode still go out —
    /// the gate must not become a blanket ban.
    #[test]
    fn unicode_keys_survive_when_the_server_advertises_unicode() {
        use super::{fastpath_events_for, InputEvent};
        use std::sync::atomic::{AtomicU64, Ordering};

        let dropped = AtomicU64::new(0);
        let mut db = ironrdp_input::Database::new();
        let events = vec![InputEvent::UnicodeKey { ch: 'e' as u32, pressed: true }];
        let out = fastpath_events_for(&mut db, events, true, &dropped);
        assert!(!out.is_empty(), "unicode must still reach a server that takes it");
        assert_eq!(0, dropped.load(Ordering::Relaxed));
    }

    /// Scancodes are the path the app uses for ASCII and must be untouched by
    /// the unicode gate — this is what actually drives a VirtualBox guest.
    #[test]
    fn scancodes_are_unaffected_by_the_unicode_gate() {
        use super::{fastpath_events_for, InputEvent};
        use std::sync::atomic::{AtomicU64, Ordering};

        let dropped = AtomicU64::new(0);
        let mut db = ironrdp_input::Database::new();
        let events = vec![
            InputEvent::Key { scancode: 0x1c, pressed: true },
            InputEvent::Key { scancode: 0x1c, pressed: false },
        ];
        let out = fastpath_events_for(&mut db, events, false, &dropped);
        assert!(!out.is_empty(), "scancodes must still be sent");
        assert_eq!(0, dropped.load(Ordering::Relaxed));
    }

    /// #422: pins that an under-declared totalLength decodes, because the error
    /// it used to raise says the opposite and we shipped two fixes built on the
    /// wrong reading before settling what it meant.
    ///
    /// `ShareControlHeader::decode` used to finish with a cross-check that the
    /// PDU it decoded is the size the header declared, and report a mismatch as
    /// `not_enough_bytes_err!(total_length, header_length)` — so `received` was
    /// the server's *declared* totalLength and `expected` the size IronRDP
    /// decoded, neither of them a count of bytes off the wire. Nothing was ever
    /// missing, so our fork dropped the check (`haven-pin-20260804`) and the
    /// frame is now simply accepted.
    ///
    /// Build the reporter's exact frame — 8565 bytes on the wire, so 8550 of
    /// MCS user_data after TPKT(4) + X224(3) + MCS SDI(8) — with a greedily
    /// decoded Pointer payload and totalLength under-declared as 24, and assert
    /// the whole payload survives. This is the gate on the pin: revert to a
    /// stock IronRDP and it fails with the reporter's error verbatim.
    #[test]
    fn under_declared_share_control_length_is_not_a_truncated_pdu() {
        use ironrdp_core::decode;
        use ironrdp_pdu::rdp::headers::{ShareControlHeader, ShareControlPdu, ShareDataPdu};

        const SHARE_CONTROL_HEADER_SIZE: usize = 2 * 3 + 4;
        const SHARE_DATA_HEADER_SIZE: usize = 1 + 1 + 2 + 1 + 1 + 2;
        const PROTOCOL_VERSION: u16 = 0x10;
        const DATA_PDU: u16 = 0x7;
        const PDU_TYPE_POINTER: u8 = 0x1b;

        // 8565 on the wire minus TPKT(4) + X224(3) + MCS SendDataIndication(8).
        let user_data = 8565 - 15;
        assert!(user_data > SHARE_CONTROL_HEADER_SIZE + SHARE_DATA_HEADER_SIZE);

        let mut b = Vec::with_capacity(user_data);
        b.extend_from_slice(&24u16.to_le_bytes()); // totalLength, under-declared
        b.extend_from_slice(&(PROTOCOL_VERSION | DATA_PDU).to_le_bytes());
        b.extend_from_slice(&1002u16.to_le_bytes()); // pduSource
        b.extend_from_slice(&0x0001_0000u32.to_le_bytes()); // shareId
        b.push(0); // padding
        b.push(2); // streamPriority = Medium
        b.extend_from_slice(&0u16.to_le_bytes()); // uncompressedLength
        b.push(PDU_TYPE_POINTER); // pduType2
        b.push(0); // compressionFlags | compressionType
        b.extend_from_slice(&0u16.to_le_bytes()); // compressedLength
        b.resize(user_data, 0);
        assert_eq!(b.len(), 8550);

        let hdr = decode::<ShareControlHeader>(&b)
            .expect("an under-declared totalLength is complete, not truncated");

        let ShareControlPdu::Data(data) = hdr.share_control_pdu else {
            panic!("expected a Share Data PDU");
        };
        let ShareDataPdu::Pointer(payload) = data.share_data_pdu else {
            panic!("expected a Pointer PDU");
        };
        // Every byte past the two headers, i.e. nothing was dropped as padding
        // on the strength of the server's wrong number.
        assert_eq!(
            payload.len(),
            user_data - SHARE_CONTROL_HEADER_SIZE - SHARE_DATA_HEADER_SIZE
        );
    }

    /// The other half of the same contract: a genuinely short buffer must still
    /// be rejected. Dropping the totalLength cross-check would be the wrong fix
    /// if it also blinded us to real truncation — it doesn't, because a short
    /// buffer fails inside the inner PDU's own decode, before the check ran.
    #[test]
    fn a_genuinely_truncated_share_control_pdu_is_still_rejected() {
        use ironrdp_core::decode;
        use ironrdp_pdu::rdp::headers::ShareControlHeader;

        const PROTOCOL_VERSION: u16 = 0x10;
        const DATA_PDU: u16 = 0x7;

        // Share control header claiming a Data PDU, then nothing at all — the
        // 8-byte share data header the type demands is simply not there.
        let mut b = Vec::new();
        b.extend_from_slice(&18u16.to_le_bytes());
        b.extend_from_slice(&(PROTOCOL_VERSION | DATA_PDU).to_le_bytes());
        b.extend_from_slice(&1002u16.to_le_bytes());
        b.extend_from_slice(&0x0001_0000u32.to_le_bytes());

        decode::<ShareControlHeader>(&b).expect_err("a missing share data header must not decode");
    }

    /// AlertReceived(InternalError) → still maps to the "rejected
    /// credentials" branch, unchanged from the #109 baseline.
    #[test]
    fn alert_internal_error_still_maps_to_credentials() {
        let raw = "Error { kind: General, source: AlertReceived(InternalError) }";
        let out = classify_raw_finalize(raw);
        assert!(
            out.starts_with("Authentication failed: server rejected credentials"),
            "credentials path regressed: {out}"
        );
    }

    /// A generic TLS-shaped error after handshake (e.g. mid-session
    /// alert) is no longer mislabeled as "TLS handshake failed". It
    /// should land in the post-handshake bucket.
    #[test]
    fn generic_tls_error_post_handshake_does_not_say_handshake_failed() {
        let raw = "Error { kind: Custom, source: Some(\"Tls protocol error during MCS\") }";
        let out = classify_raw_finalize(raw);
        assert!(
            !out.starts_with("TLS handshake failed"),
            "post-handshake TLS error must not say 'handshake failed': {out}"
        );
        assert!(
            out.contains("RDP setup"),
            "expected post-handshake framing: {out}"
        );
    }
}

/// #422: the framebuffer publish used to reallocate and copy the WHOLE image
/// on every graphics update, however small — or however far outside the image
/// the update landed.
///
/// The reporter's 38-second VirtualBox session is the case that made it hurt:
/// the server painted a desktop larger than the negotiated 1920x1080, so 4305
/// updates were skipped by ironrdp as out-of-bounds and drew nothing, yet each
/// one still cost an 8.3MB allocate-copy-free on the phone.
#[cfg(test)]
mod framebuffer_publish_tests {
    use super::*;
    use ironrdp_pdu::geometry::InclusiveRectangle;
    use ironrdp_session::image::DecodedImage;
    use ironrdp_graphics::image_processing::PixelFormat;

    const W: u16 = 64;
    const H: u16 = 32;

    /// #477: the host drains these, so a second drain must come back empty or
    /// every Audit Log view repeats the previous view's lines; and a long
    /// session must not grow the buffer without bound while nobody drains.
    #[test]
    fn perf_log_drains_once_and_stays_bounded() {
        let mut s = blank_state();

        s.push_perf("first".to_owned());
        s.push_perf("second".to_owned());
        assert_eq!(
            std::mem::take(&mut s.perf_log),
            vec!["first".to_owned(), "second".to_owned()],
            "drained in order",
        );
        assert!(s.perf_log.is_empty(), "a drain empties it");

        // A session nobody ever looks at: the oldest go, the newest stay.
        for i in 0..(MAX_PERF_LOG_LINES + 10) {
            s.push_perf(format!("line {i}"));
        }
        assert_eq!(s.perf_log.len(), MAX_PERF_LOG_LINES, "capped");
        assert_eq!(
            s.perf_log.last().map(String::as_str),
            Some(format!("line {}", MAX_PERF_LOG_LINES + 9).as_str()),
            "keeps the most recent",
        );
        assert_eq!(
            s.perf_log.first().map(String::as_str),
            Some(format!("line {}", 10).as_str()),
            "drops the oldest",
        );
    }

    fn blank_state() -> SessionState {
        SessionState {
            connected: true,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }
    }

    fn state_with_frame(fill: u8) -> Arc<RwLock<SessionState>> {
        let st = Arc::new(RwLock::new(blank_state()));
        st.write().unwrap().framebuffer = Some(FrameData {
            width: W,
            height: H,
            pixels: vec![fill; W as usize * H as usize * 4],
        });
        st
    }

    fn image_filled(v: u8) -> DecodedImage {
        let mut img = DecodedImage::new(PixelFormat::RgbA32, W, H);
        // DecodedImage exposes its buffer read-only; paint through a full-size
        // update so the test drives the same path the session does.
        let _ = &mut img;
        img
    }

    fn px(fb: &FrameData, x: usize, y: usize) -> [u8; 4] {
        let i = (y * W as usize + x) * 4;
        [fb.pixels[i], fb.pixels[i + 1], fb.pixels[i + 2], fb.pixels[i + 3]]
    }

    /// An update whose rectangle lies wholly outside the image must not touch
    /// the published buffer — and critically must not reallocate it. This is
    /// the 4305-updates case; before the fix each one replaced the whole Vec.
    #[test]
    fn an_out_of_bounds_update_leaves_the_buffer_untouched() {
        let state = state_with_frame(0xAB);
        let image = image_filled(0);
        let before_ptr = state.read().unwrap().framebuffer.as_ref().unwrap().pixels.as_ptr();

        // Same shape as the logged rects: far beyond a 64x32 image.
        let rect = InclusiveRectangle { left: 2304, top: 1517, right: 2431, bottom: 1599 };
        update_framebuffer(&state, &image, &rect);

        let s = state.read().unwrap();
        let fb = s.framebuffer.as_ref().unwrap();
        assert_eq!(
            fb.pixels.as_ptr(),
            before_ptr,
            "an out-of-bounds update reallocated the framebuffer; that realloc \
             (8.3MB at 1080p) happened 4305 times in the #422 trace",
        );
        assert!(
            fb.pixels.iter().all(|&b| b == 0xAB),
            "an out-of-bounds update must not alter published pixels",
        );
        assert_eq!(fb.width, W, "dimensions must be preserved");
    }

    /// A dimension change is the one case that still needs a fresh buffer,
    /// otherwise a resized session would publish a stale-sized frame.
    #[test]
    fn a_size_change_replaces_the_buffer() {
        let state = Arc::new(RwLock::new(blank_state()));
        state.write().unwrap().framebuffer = Some(FrameData {
            width: 8,
            height: 8,
            pixels: vec![0x11; 8 * 8 * 4],
        });
        let image = image_filled(0);
        update_framebuffer(
            &state,
            &image,
            &InclusiveRectangle { left: 0, top: 0, right: 0, bottom: 0 },
        );
        let s = state.read().unwrap();
        let fb = s.framebuffer.as_ref().unwrap();
        assert_eq!((fb.width, fb.height), (W, H), "buffer must adopt the new image size");
        assert_eq!(fb.pixels.len(), W as usize * H as usize * 4);
    }

    /// Every update, in bounds or not, still reports a dirty rect — the app
    /// layer relies on that to know a frame arrived.
    #[test]
    fn a_dirty_rect_is_still_recorded() {
        let state = state_with_frame(0);
        let image = image_filled(0);
        update_framebuffer(
            &state,
            &image,
            &InclusiveRectangle { left: 1, top: 2, right: 4, bottom: 6 },
        );
        let s = state.read().unwrap();
        assert_eq!(s.dirty_rects.len(), 1);
        assert_eq!((s.dirty_rects[0].x, s.dirty_rects[0].y), (1, 2));
        let _ = px(s.framebuffer.as_ref().unwrap(), 0, 0);
    }
}
