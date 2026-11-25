//! MS-RDPECLIP (`cliprdr`) static virtual channel glue (#890).
//!
//! Wraps [`ironrdp_cliprdr::CliprdrClient`] with thread-safe state shared
//! between UniFFI caller threads (`RdpClient::send_clipboard_text`) and the
//! blocking session loop (`ActiveStage`), supporting two-way Unicode/ANSI text
//! clipboard sync with CRLF <-> LF normalization and echo-loop suppression.
//!
//! Lock discipline: never hold `Mutex<SharedCliprdrState>` while calling any
//! method on `CliprdrClient` (whose backend callbacks acquire the same mutex)
//! or while invoking UniFFI `ClipboardCallback` into Kotlin.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ironrdp_cliprdr::backend::CliprdrBackend;
use ironrdp_cliprdr::pdu::{
    ClipboardFormat, ClipboardFormatId, ClipboardGeneralCapabilityFlags, FileContentsRequest,
    FileContentsResponse, FormatDataRequest, FormatDataResponse, LockDataId,
    OwnedFormatDataResponse,
};
use ironrdp_cliprdr::{Client, CliprdrClient, CliprdrSvcMessages};
use ironrdp_core::AsAny;
use log::{debug, error};

const TIMEOUT_SWEEP_INTERVAL: Duration = Duration::from_secs(5);

/// Normalize remote Windows CRLF text to Unix LF (stripping any trailing NULs).
pub(crate) fn crlf_to_lf(text: &str) -> String {
    text.trim_end_matches('\0').replace("\r\n", "\n")
}

/// Normalize local text to Windows CRLF without doubling existing `\r\n` pairs.
pub(crate) fn lf_to_crlf(text: &str) -> String {
    let lf = text.trim_end_matches('\0').replace("\r\n", "\n");
    lf.replace('\n', "\r\n")
}

#[derive(Debug)]
pub(crate) struct SharedCliprdrState {
    /// Latest local text (LF-normalized) available for delayed rendering when
    /// the remote sends `FormatDataRequest`.
    local_text: Option<String>,
    /// Most recent text observed in either direction (LF-normalized). Prevents
    /// re-advertising unchanged clipboard content back to the server.
    last_synced_text: Option<String>,
    /// Most recent outbound text advertised by the local side. Suppresses echo
    /// callbacks if a server re-announces our own copy back to us.
    last_outbound_text: Option<String>,
    /// Set by `on_request_format_list` once `MonitorReady` arrives so we send
    /// the mandatory initial `Capabilities + TemporaryDirectory + FormatList`
    /// bundle exactly once.
    send_initial_format_list: bool,
    /// Set when `local_text` was updated and needs a `FormatList` advertisement
    /// once the channel is `Ready`.
    dirty_local: bool,
    /// Queued responses to remote `FormatDataRequest` PDUs.
    pending_format_data_responses: VecDeque<OwnedFormatDataResponse>,
    /// Latest remote text format announced via `FormatList` awaiting `initiate_paste`.
    pending_paste_format: Option<ClipboardFormatId>,
    /// FIFO of format IDs for sent `FormatDataRequest` PDUs so each incoming
    /// `FormatDataResponse` is decoded with the matching character set.
    in_flight_paste_formats: VecDeque<ClipboardFormatId>,
    /// Decoded remote clipboard strings (LF-normalized) ready for Kotlin.
    pending_remote_texts: VecDeque<String>,
    last_timeout_sweep: Instant,
}

impl SharedCliprdrState {
    pub(crate) fn new() -> Self {
        Self {
            local_text: None,
            last_synced_text: None,
            last_outbound_text: None,
            send_initial_format_list: false,
            dirty_local: false,
            pending_format_data_responses: VecDeque::new(),
            pending_paste_format: None,
            in_flight_paste_formats: VecDeque::new(),
            pending_remote_texts: VecDeque::new(),
            last_timeout_sweep: Instant::now(),
        }
    }

    /// Reset per-channel transient queues at the start of a connection (or a
    /// followed server-redirection hop) while keeping any pre-queued local text.
    pub(crate) fn reset_for_connection(&mut self) {
        self.send_initial_format_list = false;
        self.dirty_local = self.local_text.is_some();
        self.pending_format_data_responses.clear();
        self.pending_paste_format = None;
        self.in_flight_paste_formats.clear();
        self.pending_remote_texts.clear();
        self.last_timeout_sweep = Instant::now();
    }

    /// Queue local clipboard text for outbound advertisement to the remote.
    pub(crate) fn queue_local_text(&mut self, raw: String) {
        let normalized = crlf_to_lf(&raw);
        if normalized.is_empty() {
            return;
        }
        if self.last_synced_text.as_deref() == Some(normalized.as_str()) {
            return;
        }
        debug!(
            "CLIPRDR queued local text ({} chars)",
            normalized.chars().count()
        );
        self.local_text = Some(normalized.clone());
        self.last_synced_text = Some(normalized.clone());
        self.last_outbound_text = Some(normalized);
        self.dirty_local = true;
    }
}

#[derive(Debug)]
struct HavenCliprdrBackend {
    state: Arc<Mutex<SharedCliprdrState>>,
}

impl AsAny for HavenCliprdrBackend {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
        self
    }
}

impl CliprdrBackend for HavenCliprdrBackend {
    fn temporary_directory(&self) -> &str {
        ".cliprdr"
    }

    fn client_capabilities(&self) -> ClipboardGeneralCapabilityFlags {
        // `Cliprdr` automatically adds `USE_LONG_FORMAT_NAMES`. File-stream
        // flags stay off until file clipboard transfer is wired.
        ClipboardGeneralCapabilityFlags::empty()
    }

    fn on_ready(&mut self) {
        debug!("CLIPRDR channel ready");
    }

    fn on_request_format_list(&mut self) {
        if let Ok(mut st) = self.state.lock() {
            st.send_initial_format_list = true;
        }
    }

    fn on_process_negotiated_capabilities(&mut self, capabilities: ClipboardGeneralCapabilityFlags) {
        debug!("CLIPRDR negotiated capabilities: {capabilities:?}");
    }

    fn on_remote_copy(&mut self, available_formats: &[ClipboardFormat]) {
        let chosen = available_formats
            .iter()
            .find(|f| f.id() == ClipboardFormatId::CF_UNICODETEXT)
            .or_else(|| {
                available_formats
                    .iter()
                    .find(|f| f.id() == ClipboardFormatId::CF_TEXT)
            })
            .or_else(|| {
                available_formats
                    .iter()
                    .find(|f| f.id() == ClipboardFormatId::CF_OEMTEXT)
            })
            .map(ClipboardFormat::id);

        if let Ok(mut st) = self.state.lock() {
            if let Some(fmt_id) = chosen {
                debug!("CLIPRDR remote FormatList includes text ({fmt_id:?})");
                st.pending_paste_format = Some(fmt_id);
            } else {
                debug!(
                    "CLIPRDR remote FormatList has no text format ({} formats)",
                    available_formats.len()
                );
                st.pending_paste_format = None;
                // Remote clipboard replaced with non-text content (e.g. image
                // or files), so allow re-sending the previous local text.
                st.last_synced_text = None;
                st.last_outbound_text = None;
            }
        }
    }

    fn on_format_data_request(&mut self, request: FormatDataRequest) {
        if let Ok(mut st) = self.state.lock() {
            let response = match (request.format, st.local_text.as_deref()) {
                (ClipboardFormatId::CF_UNICODETEXT, Some(text)) => {
                    OwnedFormatDataResponse::new_unicode_string(&lf_to_crlf(text))
                }
                (ClipboardFormatId::CF_TEXT | ClipboardFormatId::CF_OEMTEXT, Some(text)) => {
                    OwnedFormatDataResponse::new_string(&lf_to_crlf(text))
                }
                _ => OwnedFormatDataResponse::new_error(),
            };
            st.pending_format_data_responses.push_back(response);
        }
    }

    fn on_format_data_response(&mut self, response: FormatDataResponse<'_>) {
        let fmt_id = self
            .state
            .lock()
            .ok()
            .and_then(|mut st| st.in_flight_paste_formats.pop_front())
            .unwrap_or(ClipboardFormatId::CF_UNICODETEXT);

        if response.is_error() {
            debug!("CLIPRDR FormatDataResponse reported error for {fmt_id:?}");
            return;
        }

        let decoded = match fmt_id {
            ClipboardFormatId::CF_TEXT | ClipboardFormatId::CF_OEMTEXT => response.to_string(),
            _ => response.to_unicode_string(),
        };

        let raw = match decoded {
            Ok(s) => s,
            Err(e) => {
                error!("CLIPRDR FormatDataResponse decode failed ({fmt_id:?}): {e}");
                return;
            }
        };

        let text = crlf_to_lf(&raw);
        if text.is_empty() {
            return;
        }

        if let Ok(mut st) = self.state.lock() {
            st.last_synced_text = Some(text.clone());
            if st.last_outbound_text.as_deref() == Some(text.as_str()) {
                return;
            }
            st.last_outbound_text = None;
            // Log character count only — never raw clipboard text (#518).
            debug!(
                "CLIPRDR received remote clipboard ({} chars)",
                text.chars().count()
            );
            st.pending_remote_texts.push_back(text);
        }
    }

    fn on_file_contents_request(&mut self, request: FileContentsRequest) {
        debug!(
            "CLIPRDR ignoring unexpected FileContentsRequest (stream_id={})",
            request.stream_id
        );
    }

    fn on_file_contents_response(&mut self, _response: FileContentsResponse<'_>) {}

    fn on_lock(&mut self, _data_id: LockDataId) {}

    fn on_unlock(&mut self, _data_id: LockDataId) {}
}

pub(crate) fn new_client(state: Arc<Mutex<SharedCliprdrState>>) -> CliprdrClient {
    CliprdrClient::new(Box::new(HavenCliprdrBackend { state }))
}

/// Step `CliprdrClient` against `SharedCliprdrState`, returning outbound SVC
/// message batches and any newly received remote clipboard texts.
pub(crate) fn step_cliprdr(
    cliprdr: &mut CliprdrClient,
    state: &Mutex<SharedCliprdrState>,
) -> (Vec<CliprdrSvcMessages<Client>>, Vec<String>) {
    let is_ready = cliprdr.is_ready();

    let (advertise_local, responses, paste_fmt, run_timeouts) = {
        let Ok(mut st) = state.lock() else {
            return (Vec::new(), Vec::new());
        };

        let advertise = if st.send_initial_format_list {
            st.send_initial_format_list = false;
            st.dirty_local = false;
            Some(st.local_text.is_some())
        } else if is_ready && st.dirty_local {
            st.dirty_local = false;
            Some(st.local_text.is_some())
        } else {
            None
        };

        let responses: Vec<OwnedFormatDataResponse> = if is_ready {
            st.pending_format_data_responses.drain(..).collect()
        } else {
            Vec::new()
        };

        let paste_fmt = if is_ready {
            let fmt = st.pending_paste_format.take();
            if let Some(id) = fmt {
                st.in_flight_paste_formats.push_back(id);
            }
            fmt
        } else {
            None
        };

        let sweep = is_ready && st.last_timeout_sweep.elapsed() >= TIMEOUT_SWEEP_INTERVAL;
        if sweep {
            st.last_timeout_sweep = Instant::now();
        }

        (advertise, responses, paste_fmt, sweep)
    };

    let mut out_batches = Vec::new();

    if let Some(has_text) = advertise_local {
        let formats: &[ClipboardFormat] = if has_text {
            &[ClipboardFormat::new(ClipboardFormatId::CF_UNICODETEXT)]
        } else {
            &[]
        };
        match cliprdr.initiate_copy(formats) {
            Ok(msgs) => out_batches.push(msgs),
            Err(e) => error!("CLIPRDR initiate_copy failed: {e}"),
        }
    }

    for resp in responses {
        match cliprdr.submit_format_data(resp) {
            Ok(msgs) => out_batches.push(msgs),
            Err(e) => error!("CLIPRDR submit_format_data failed: {e}"),
        }
    }

    if let Some(fmt_id) = paste_fmt {
        match cliprdr.initiate_paste(fmt_id) {
            Ok(msgs) => out_batches.push(msgs),
            Err(e) => {
                error!("CLIPRDR initiate_paste({fmt_id:?}) failed: {e}");
                if let Ok(mut st) = state.lock() {
                    let _ = st.in_flight_paste_formats.pop_back();
                }
            }
        }
    }

    if run_timeouts {
        match cliprdr.drive_timeouts() {
            Ok(msgs) => out_batches.push(msgs),
            Err(e) => error!("CLIPRDR drive_timeouts failed: {e}"),
        }
    }

    let remote_texts = state
        .lock()
        .map(|mut st| st.pending_remote_texts.drain(..).collect())
        .unwrap_or_default();

    (out_batches, remote_texts)
}

/// Drain pending CLIPRDR actions through `ActiveStage`, returning wire-encoded
/// X.224 static-channel frames and newly decoded remote clipboard texts.
pub(crate) fn flush_cliprdr(
    active_stage: &mut ironrdp_session::ActiveStage,
    state: &Mutex<SharedCliprdrState>,
) -> (Vec<Vec<u8>>, Vec<String>) {
    let (batches, remote_texts) = {
        let Some(cliprdr) = active_stage.get_svc_processor_mut::<CliprdrClient>() else {
            return (Vec::new(), Vec::new());
        };
        step_cliprdr(cliprdr, state)
    };

    let mut frames = Vec::new();
    for msgs in batches {
        let raw_msgs: Vec<ironrdp_svc::SvcMessage> = msgs.into();
        if raw_msgs.is_empty() {
            continue;
        }
        match active_stage.process_svc_processor_messages(CliprdrSvcMessages::<Client>::new(raw_msgs)) {
            Ok(bytes) if !bytes.is_empty() => frames.push(bytes),
            Ok(_) => {}
            Err(e) => error!("CLIPRDR SVC encode error: {e}"),
        }
    }

    (frames, remote_texts)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ironrdp_cliprdr::CliprdrServer;
    use ironrdp_svc::{SvcMessage, SvcProcessor};

    #[derive(Debug, Default)]
    struct MockServerState {
        remote_formats: Vec<ClipboardFormat>,
        received_text: Option<String>,
        serve_text: Option<String>,
    }

    #[derive(Debug)]
    struct MockServerBackend {
        state: Arc<Mutex<MockServerState>>,
    }

    impl AsAny for MockServerBackend {
        fn as_any(&self) -> &dyn std::any::Any {
            self
        }

        fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
            self
        }
    }

    impl CliprdrBackend for MockServerBackend {
        fn temporary_directory(&self) -> &str {
            ".srv"
        }

        fn client_capabilities(&self) -> ClipboardGeneralCapabilityFlags {
            ClipboardGeneralCapabilityFlags::empty()
        }

        fn on_ready(&mut self) {}
        fn on_request_format_list(&mut self) {}
        fn on_process_negotiated_capabilities(&mut self, _: ClipboardGeneralCapabilityFlags) {}

        fn on_remote_copy(&mut self, available_formats: &[ClipboardFormat]) {
            self.state.lock().unwrap().remote_formats = available_formats.to_vec();
        }

        fn on_format_data_request(&mut self, request: FormatDataRequest) {
            assert_eq!(request.format, ClipboardFormatId::CF_UNICODETEXT);
        }

        fn on_format_data_response(&mut self, response: FormatDataResponse<'_>) {
            if !response.is_error() {
                let s = response.to_unicode_string().expect("utf16 response");
                self.state.lock().unwrap().received_text = Some(s);
            }
        }

        fn on_file_contents_request(&mut self, _: FileContentsRequest) {}
        fn on_file_contents_response(&mut self, _: FileContentsResponse<'_>) {}
        fn on_lock(&mut self, _: LockDataId) {}
        fn on_unlock(&mut self, _: LockDataId) {}
    }

    fn deliver_to_client(
        server_msgs: Vec<SvcMessage>,
        client: &mut CliprdrClient,
        client_state: &Mutex<SharedCliprdrState>,
    ) -> (Vec<SvcMessage>, Vec<String>) {
        let mut replies = Vec::new();
        for msg in server_msgs {
            let bytes = msg.encode_unframed_pdu().expect("encode server pdu");
            replies.extend(client.process(&bytes).expect("client process"));
        }
        let (batches, remote_texts) = step_cliprdr(client, client_state);
        for batch in batches {
            let v: Vec<SvcMessage> = batch.into();
            replies.extend(v);
        }
        (replies, remote_texts)
    }

    fn deliver_to_server(client_msgs: Vec<SvcMessage>, server: &mut CliprdrServer) -> Vec<SvcMessage> {
        let mut replies = Vec::new();
        for msg in client_msgs {
            let bytes = msg.encode_unframed_pdu().expect("encode client pdu");
            replies.extend(server.process(&bytes).expect("server process"));
        }
        replies
    }

    fn setup_pair() -> (
        CliprdrClient,
        Arc<Mutex<SharedCliprdrState>>,
        CliprdrServer,
        Arc<Mutex<MockServerState>>,
    ) {
        let client_state = Arc::new(Mutex::new(SharedCliprdrState::new()));
        let mut client = new_client(Arc::clone(&client_state));
        let server_state = Arc::new(Mutex::new(MockServerState::default()));
        let mut server = CliprdrServer::new(Box::new(MockServerBackend {
            state: Arc::clone(&server_state),
        }));

        // Server sends Capabilities + MonitorReady on channel start.
        let init_server = server.start().expect("server start");
        assert!(!client.is_ready());
        let (client_init, _) = deliver_to_client(init_server, &mut client, &client_state);
        let server_ack = deliver_to_server(client_init, &mut server);
        let (after_ack, _) = deliver_to_client(server_ack, &mut client, &client_state);
        assert!(after_ack.is_empty());
        assert!(client.is_ready(), "client should reach Ready after MonitorReady handshake");
        assert!(server.is_ready(), "server should reach Ready after initial FormatList");

        (client, client_state, server, server_state)
    }

    #[test]
    fn line_ending_helpers_preserve_existing_crlf_and_strip_trailing_nuls() {
        assert_eq!(crlf_to_lf("one\r\ntwo\nthree\0\0"), "one\ntwo\nthree");
        assert_eq!(lf_to_crlf("one\r\ntwo\nthree\0"), "one\r\ntwo\r\nthree");
    }

    #[test]
    fn local_to_remote_text_copy_advertises_and_serves_crlf_utf16() {
        let (mut client, client_state, mut server, server_state) = setup_pair();

        client_state
            .lock()
            .unwrap()
            .queue_local_text("hello\nwindows 🌍".to_owned());

        let (batches, _) = step_cliprdr(&mut client, &client_state);
        let client_msgs: Vec<SvcMessage> = batches.into_iter().flat_map(Vec::<SvcMessage>::from).collect();
        let server_ack = deliver_to_server(client_msgs, &mut server);
        let _ = deliver_to_client(server_ack, &mut client, &client_state);

        assert_eq!(
            server_state.lock().unwrap().remote_formats,
            vec![ClipboardFormat::new(ClipboardFormatId::CF_UNICODETEXT)]
        );

        // Remote user presses Ctrl+V -> server sends FormatDataRequest(CF_UNICODETEXT).
        let paste_req: Vec<SvcMessage> = server
            .initiate_paste(ClipboardFormatId::CF_UNICODETEXT)
            .expect("server initiate_paste")
            .into();
        let (client_data, _) = deliver_to_client(paste_req, &mut client, &client_state);
        let _ = deliver_to_server(client_data, &mut server);

        assert_eq!(
            server_state.lock().unwrap().received_text.as_deref(),
            Some("hello\r\nwindows 🌍")
        );
    }

    #[test]
    fn remote_to_local_text_copy_fetches_utf16_and_normalizes_to_lf() {
        let (mut client, client_state, mut server, server_state) = setup_pair();
        server_state.lock().unwrap().serve_text = Some("line 1\r\nline 2 — café".to_owned());

        // Remote user presses Ctrl+C -> server announces CF_UNICODETEXT.
        let server_copy: Vec<SvcMessage> = server
            .initiate_copy(&[ClipboardFormat::new(ClipboardFormatId::CF_UNICODETEXT)])
            .expect("server initiate_copy")
            .into();
        let (client_req, remote_texts) = deliver_to_client(server_copy, &mut client, &client_state);
        assert!(remote_texts.is_empty(), "waiting for FormatDataResponse");
        let _ = deliver_to_server(client_req, &mut server);

        // Server answers the FormatDataRequest with UTF-16LE CRLF data.
        let text_to_serve = server_state.lock().unwrap().serve_text.clone().unwrap();
        let server_resp: Vec<SvcMessage> = server
            .submit_format_data(OwnedFormatDataResponse::new_unicode_string(&text_to_serve))
            .expect("server submit_format_data")
            .into();
        let (_, remote_texts) = deliver_to_client(server_resp, &mut client, &client_state);

        assert_eq!(remote_texts, vec!["line 1\nline 2 — café".to_owned()]);

        // Echo-loop guard: re-queuing the exact text just received from the
        // remote must not re-advertise FormatList back to the server.
        client_state
            .lock()
            .unwrap()
            .queue_local_text("line 1\nline 2 — café".to_owned());
        let (batches, _) = step_cliprdr(&mut client, &client_state);
        assert!(batches.is_empty(), "duplicate local queue must be suppressed");
    }

    #[test]
    fn pre_handshake_local_text_waits_for_monitor_ready() {
        let client_state = Arc::new(Mutex::new(SharedCliprdrState::new()));
        client_state
            .lock()
            .unwrap()
            .queue_local_text("queued early".to_owned());

        let mut client = new_client(Arc::clone(&client_state));
        // Before MonitorReady arrives, step_cliprdr must not emit premature PDUs.
        let (early_batches, _) = step_cliprdr(&mut client, &client_state);
        assert!(early_batches.is_empty());

        let server_state = Arc::new(Mutex::new(MockServerState::default()));
        let mut server = CliprdrServer::new(Box::new(MockServerBackend {
            state: Arc::clone(&server_state),
        }));
        let init_server = server.start().expect("server start");
        let (client_init, _) = deliver_to_client(init_server, &mut client, &client_state);
        let server_ack = deliver_to_server(client_init, &mut server);
        let _ = deliver_to_client(server_ack, &mut client, &client_state);

        assert!(client.is_ready());
        assert_eq!(
            server_state.lock().unwrap().remote_formats,
            vec![ClipboardFormat::new(ClipboardFormatId::CF_UNICODETEXT)]
        );
    }
}
