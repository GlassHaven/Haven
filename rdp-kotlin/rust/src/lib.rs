use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex, RwLock};
use log::{debug, error, info};

mod egfx;

uniffi::setup_scaffolding!();

fn init_logging() {
    use std::sync::Once;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("RdpNative"),
        );
    });
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
}

/// Internal state for the RDP session.
struct SessionState {
    connected: bool,
    framebuffer: Option<FrameData>,
    dirty_rects: Vec<RdpRect>,
    frame_callback: Option<Arc<dyn FrameCallback>>,
    clipboard_callback: Option<Arc<dyn ClipboardCallback>>,
    session_callback: Option<Arc<dyn SessionCallback>>,
    shutdown: bool,
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
    input_queue: Arc<Mutex<Vec<InputEvent>>>,
    session_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

#[uniffi::export]
impl RdpClient {
    #[uniffi::constructor]
    pub fn new(config: RdpConfig) -> Self {
        init_logging();
        Self {
            config,
            state: Arc::new(RwLock::new(SessionState {
                connected: false,
                framebuffer: None,
                dirty_rects: Vec::new(),
                frame_callback: None,
                clipboard_callback: None,
                session_callback: None,
                shutdown: false,
            })),
            input_queue: Arc::new(Mutex::new(Vec::new())),
            session_thread: Mutex::new(None),
        }
    }

    pub fn connect(&self, host: String, port: u16) -> Result<(), RdpError> {
        let addr = format!("{}:{}", host, port);
        let stream = TcpStream::connect(&addr).map_err(|e| {
            // TCP-level failure is surfaced synchronously through the return
            // value — no thread yet, no callback to fire.
            error!("TCP connect to {} failed: {}", addr, e);
            RdpError::ConnectionFailed
        })?;
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

        let config = self.config.clone();
        let state = Arc::clone(&self.state);
        let input_queue = Arc::clone(&self.input_queue);
        let server_name = host.clone();

        let handle = std::thread::Builder::new()
            .name("rdp-session".into())
            .spawn(move || {
                let result = run_rdp_session(stream, &config, &state, &input_queue, &server_name, server_addr);
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

    pub fn get_dirty_rects(&self) -> Vec<RdpRect> {
        if let Ok(mut s) = self.state.write() {
            std::mem::take(&mut s.dirty_rects)
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
}

/// Build the ironrdp Config with all required fields.
fn build_config(config: &RdpConfig) -> ironrdp_connector::Config {
    use ironrdp_connector::*;
    use ironrdp_pdu::gcc;

    Config {
        credentials: Credentials::UsernamePassword {
            username: config.username.clone().into(),
            password: config.password.clone().into(),
        },
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
        keyboard_layout: 0x0409, // US English
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
                    Codec {
                        id: 0,
                        property: CodecProperty::NsCodec(NsCodec {
                            is_dynamic_fidelity_allowed: true,
                            is_subsampling_allowed: true,
                            color_loss_level: 3,
                        }),
                    },
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
        enable_server_pointer: false,
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
/// non-exhaustive additions). These tend to wrap TLS-layer failures
/// (rustls alert received during CredSSP network IO) — preserve the
/// pre-existing patterns so the Windows-NLA case (#109 surf5726) still
/// maps to the friendly "server rejected credentials" message.
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
    } else if raw.contains("Tls") || raw.contains("TLS") || raw.contains("unexpected_message") {
        format!("TLS handshake failed: {}", raw)
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

/// Create a rustls TLS connector that accepts any server certificate.
/// RDP servers typically use self-signed certificates.
fn create_tls_config() -> Result<rustls::ClientConfig, RdpError> {
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};

    #[derive(Debug)]
    struct AcceptAnyServerCert;

    impl ServerCertVerifier for AcceptAnyServerCert {
        fn verify_server_cert(
            &self,
            _end_entity: &rustls::pki_types::CertificateDer<'_>,
            _intermediates: &[rustls::pki_types::CertificateDer<'_>],
            _server_name: &rustls::pki_types::ServerName<'_>,
            _ocsp_response: &[u8],
            _now: rustls::pki_types::UnixTime,
        ) -> Result<ServerCertVerified, rustls::Error> {
            Ok(ServerCertVerified::assertion())
        }

        fn verify_tls12_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn verify_tls13_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
            vec![
                rustls::SignatureScheme::RSA_PKCS1_SHA256,
                rustls::SignatureScheme::RSA_PKCS1_SHA384,
                rustls::SignatureScheme::RSA_PKCS1_SHA512,
                rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
                rustls::SignatureScheme::RSA_PSS_SHA256,
                rustls::SignatureScheme::RSA_PSS_SHA384,
                rustls::SignatureScheme::RSA_PSS_SHA512,
                rustls::SignatureScheme::ED25519,
            ]
        }
    }

    // Explicitly use ring provider — auto-detection panics on Android
    let provider = rustls::crypto::ring::default_provider();
    let mut config = rustls::ClientConfig::builder_with_provider(provider.into())
        .with_safe_default_protocol_versions()
        .map_err(|_| RdpError::TlsError)?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAnyServerCert))
        .with_no_client_auth();
    // Honour SSLKEYLOGFILE (no-op when the env var is unset). Useful for host
    // wireshark debugging via rdp-cli; on device the env var is never set.
    config.key_log = Arc::new(rustls::KeyLogFile::new());
    Ok(config)
}

/// Run the blocking RDP session on a dedicated thread.
///
/// Returns the raw error message on failure so Kotlin can surface it to the
/// user rather than swallowing every failure into a generic "Connection
/// failed" — which is what happened before and produced the "nothing
/// happens, empty Desktop screen" symptom in #106.
fn run_rdp_session(
    stream: TcpStream,
    config: &RdpConfig,
    state: &Arc<RwLock<SessionState>>,
    input_queue: &Arc<Mutex<Vec<InputEvent>>>,
    server_name: &str,
    server_addr: SocketAddr,
) -> Result<(), String> {
    use ironrdp_blocking::{connect_begin, connect_finalize, mark_as_upgraded, Framed};
    use ironrdp_connector::ServerName;
    use ironrdp_session::{ActiveStage, ActiveStageOutput};
    use ironrdp_session::image::DecodedImage;
    use ironrdp_graphics::image_processing::PixelFormat;

    let rdp_config = build_config(config);
    let mut connector = ironrdp_connector::ClientConnector::new(rdp_config, server_addr)
        .with_static_channel(
            ironrdp_dvc::DrdynvcClient::new()
                .with_dynamic_channel(crate::egfx::EgfxProcessor::new(state.clone())),
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

    // Phase 2: TLS upgrade
    let tls_config = create_tls_config()
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

    let tls_stream = rustls::StreamOwned::new(tls_conn, socket);
    let mut tls_framed = Framed::new_with_leftover(tls_stream, leftover);

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
    let raw_cert_der = tls_framed
        .get_inner()
        .0
        .conn
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| cert.as_ref().to_vec())
        .ok_or_else(|| "no peer certificate after TLS handshake".to_string())?;

    let server_public_key = {
        use x509_cert::der::Decode as _;
        let cert = x509_cert::Certificate::from_der(&raw_cert_der)
            .map_err(|e| format!("parse server cert: {}", e))?;
        cert.tbs_certificate
            .subject_public_key_info
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

    let mut active_stage = ActiveStage::new(connection_result);

    // Handshake is done; shrink the read timeout to 100ms so the session
    // loop can poll the shutdown flag promptly. WouldBlock/TimedOut are
    // handled by `continue` in the loop below.
    if let Err(e) = stream_ctl.set_read_timeout(Some(std::time::Duration::from_millis(100))) {
        error!("set_read_timeout post-handshake failed (non-fatal): {}", e);
    }

    // Input state tracking
    let mut input_db = ironrdp_input::Database::new();

    // Active session loop
    loop {
        // Check for shutdown
        if let Ok(s) = state.read() {
            if s.shutdown {
                break;
            }
        }

        // Process queued input events
        let pending_inputs: Vec<InputEvent> = {
            if let Ok(mut q) = input_queue.lock() {
                std::mem::take(&mut *q)
            } else {
                Vec::new()
            }
        };

        for event in pending_inputs {
            let fastpath_events = match event {
                InputEvent::Key { scancode, pressed } => {
                    let op = if pressed {
                        ironrdp_input::Operation::KeyPressed(
                            ironrdp_input::Scancode::from_u16(scancode)
                        )
                    } else {
                        ironrdp_input::Operation::KeyReleased(
                            ironrdp_input::Scancode::from_u16(scancode)
                        )
                    };
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::UnicodeKey { ch, pressed } => {
                    if let Some(c) = char::from_u32(ch) {
                        let op = if pressed {
                            ironrdp_input::Operation::UnicodeKeyPressed(c)
                        } else {
                            ironrdp_input::Operation::UnicodeKeyReleased(c)
                        };
                        input_db.apply(std::iter::once(op))
                    } else {
                        smallvec::SmallVec::new()
                    }
                }
                InputEvent::MouseMove { x, y } => {
                    let op = ironrdp_input::Operation::MouseMove(
                        ironrdp_input::MousePosition { x, y }
                    );
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::MouseButton { button, pressed } => {
                    let btn = match button {
                        MouseButton::Left => ironrdp_input::MouseButton::Left,
                        MouseButton::Right => ironrdp_input::MouseButton::Right,
                        MouseButton::Middle => ironrdp_input::MouseButton::Middle,
                    };
                    let op = if pressed {
                        ironrdp_input::Operation::MouseButtonPressed(btn)
                    } else {
                        ironrdp_input::Operation::MouseButtonReleased(btn)
                    };
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::MouseWheel { vertical: _, delta } => {
                    let op = ironrdp_input::Operation::WheelRotations(
                        ironrdp_input::WheelRotations {
                            is_vertical: true,
                            rotation_units: delta as i16,
                        }
                    );
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::ClipboardText(_text) => {
                    // Clipboard handled via CLIPRDR channel, not input
                    smallvec::SmallVec::new()
                }
            };

            if !fastpath_events.is_empty() {
                match active_stage.process_fastpath_input(&mut image, &fastpath_events) {
                    Ok(outputs) => {
                        for output in outputs {
                            if let ActiveStageOutput::ResponseFrame(frame) = output {
                                if let Err(e) = tls_framed.write_all(&frame) {
                                    error!("Write input error: {:?}", e);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        error!("Input processing error: {:?}", e);
                    }
                }
            }
        }

        // Read server PDU
        match tls_framed.read_pdu() {
            Ok((action, frame)) => {
                match active_stage.process(&mut image, action, &frame) {
                    Ok(outputs) => {
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
                                    update_framebuffer(state, &image, &rect);
                                }
                                ActiveStageOutput::Terminate(reason) => {
                                    error!("Server disconnect: {}", reason);
                                    break;
                                }
                                ActiveStageOutput::DeactivateAll(_cas) => {
                                    // Server-initiated deactivation-reactivation
                                    // Would need to handle reconnection here
                                    break;
                                }
                                _ => {}
                            }
                        }
                    }
                    Err(e) => {
                        let msg = format!("{:?}", e);
                        if msg.contains("unhandled") || msg.contains("unsupported") {
                            // Try to decode as slow-path bitmap update
                            if try_handle_slow_path_bitmap(&frame, state) {
                                debug!("Decoded slow-path bitmap update");
                            } else {
                                debug!("Skipping unhandled PDU: {}", msg);
                            }
                        } else {
                            error!("Session process error: {}", msg);
                            break;
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

    Ok(())
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
    use ironrdp_connector::legacy::{decode_send_data_indication, decode_io_channel, IoChannelPdu};
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

                        // ARGB_8888 in native little-endian: bytes [B, G, R, A]
                        let di = (dst_row_y * fb_width + dst_col_x) * 4;
                        if di + 3 < fb_data.len() {
                            fb_data[di] = b;
                            fb_data[di + 1] = g;
                            fb_data[di + 2] = r;
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

    // Convert BGRA (ironrdp RgbA32) to Android ARGB_8888 native LE: [B,G,R,A]
    // ironrdp BGRA is already [B,G,R,A] in memory — same layout!
    let pixel_count = fb_width * fb_height;
    let argb = pixel_data[..pixel_count * 4].to_vec();

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
        s.framebuffer = Some(FrameData {
            width: fb_width as u16,
            height: fb_height as u16,
            pixels: argb,
        });
        s.dirty_rects.push(rdp_rect.clone());
        s.frame_callback.clone()
    };
    // Invoke callback outside the lock — Kotlin's onFrameUpdate calls
    // getFramebuffer() which needs a read lock.
    if let Some(cb) = frame_cb {
        cb.on_frame_update(rdp_rect.x, rdp_rect.y, rdp_rect.width, rdp_rect.height);
    }
}
