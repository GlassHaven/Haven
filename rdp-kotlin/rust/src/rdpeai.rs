//! MS-RDPEAI (`AUDIO_INPUT` dynamic virtual channel) microphone capture
//! redirection (#890).
//!
//! Raw audio samples are never logged (`#518` PII scrub policy): only channel
//! negotiation and format metadata are written to logs.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use ironrdp_dvc::DrdynvcClient;
use ironrdp_pdu::pdu_other_err;
use ironrdp_rdpeai::client::{AudioPacketSink, DvcUplink, RdpeaiCaptureHandler, RdpeaiClient};
use ironrdp_rdpeai::pdu::{pcm_format, OpenReplyPdu};
use ironrdp_rdpsnd::codec::AudioEncoder;
use ironrdp_rdpsnd::pdu::{AudioFormat, WaveFormat};
use ironrdp_svc::SvcMessage;
use log::{debug, info, warn};

use crate::{MicrophoneCallback, SessionState};

const MAX_OUTBOUND_BATCHES: usize = 96;

static SUPPORTED_CAPTURE_FORMATS: std::sync::LazyLock<Vec<AudioFormat>> =
    std::sync::LazyLock::new(|| {
        vec![
            pcm_format(1, 48_000, 16),
            pcm_format(2, 48_000, 16),
            pcm_format(1, 44_100, 16),
            pcm_format(2, 44_100, 16),
            pcm_format(1, 22_050, 16),
            pcm_format(1, 16_000, 16),
            pcm_format(1, 8_000, 16),
        ]
    });

fn format_rank(fmt: &AudioFormat) -> u8 {
    match fmt.format {
        WaveFormat::PCM if fmt.bits_per_sample == 16 => {
            if fmt.n_samples_per_sec == 48_000
                || fmt.n_samples_per_sec == 44_100
                || fmt.n_samples_per_sec == 16_000
            {
                0
            } else {
                1
            }
        }
        WaveFormat::DVI_ADPCM | WaveFormat::ADPCM => 2,
        WaveFormat::ALAW | WaveFormat::MULAW => 3,
        WaveFormat::PCM => 4,
        _ => 5,
    }
}

fn is_capturable_on_android(fmt: &AudioFormat) -> bool {
    (1..=2).contains(&fmt.n_channels)
        && (8_000..=48_000).contains(&fmt.n_samples_per_sec)
        && AudioEncoder::supports(fmt)
}

pub(crate) fn select_preferred_input_formats(server_formats: &[AudioFormat]) -> Vec<AudioFormat> {
    let mut chosen: Vec<AudioFormat> = server_formats
        .iter()
        .filter(|fmt| is_capturable_on_android(fmt))
        .cloned()
        .collect();
    chosen.sort_by_key(format_rank);
    chosen
}

struct ActiveCapture {
    sink: AudioPacketSink,
    encoder: Option<AudioEncoder>,
    sample_rate: u32,
    channels: u16,
    frames_per_packet: u32,
    sample_buf: Vec<i16>,
    encode_buf: Vec<u8>,
}

pub(crate) struct SharedMicrophoneState {
    active: Mutex<Option<ActiveCapture>>,
    outbound_svc: Mutex<VecDeque<Vec<SvcMessage>>>,
    capturing: AtomicBool,
}

impl SharedMicrophoneState {
    pub(crate) fn new() -> Self {
        Self {
            active: Mutex::new(None),
            outbound_svc: Mutex::new(VecDeque::new()),
            capturing: AtomicBool::new(false),
        }
    }

    pub(crate) fn reset_for_connection(&self) {
        if let Ok(mut act) = self.active.lock() {
            *act = None;
        }
        if let Ok(mut q) = self.outbound_svc.lock() {
            q.clear();
        }
        self.capturing.store(false, Ordering::Release);
    }

    pub(crate) fn shutdown_all(&self, session_state: &RwLock<SessionState>) {
        let was_active = if let Ok(mut act) = self.active.lock() {
            act.take().is_some()
        } else {
            false
        };
        self.capturing.store(false, Ordering::Release);
        if let Ok(mut q) = self.outbound_svc.lock() {
            q.clear();
        }
        if was_active {
            if let Some(cb) = session_state.read().ok().and_then(|s| s.mic_callback.clone()) {
                cb.on_mic_close();
            }
        }
    }

    pub(crate) fn is_capturing(&self) -> bool {
        self.capturing.load(Ordering::Acquire)
    }

    pub(crate) fn push_pcm(&self, pcm_s16le: Vec<u8>) -> bool {
        if pcm_s16le.len() < 2 || !self.is_capturing() {
            return false;
        }
        let Ok(mut guard) = self.active.lock() else {
            return false;
        };
        let Some(act) = guard.as_mut() else {
            return false;
        };
        match &mut act.encoder {
            None => {
                (act.sink)(pcm_s16le);
                true
            }
            Some(encoder) => {
                act.sample_buf.clear();
                act.sample_buf.extend(
                    pcm_s16le
                        .chunks_exact(2)
                        .map(|pair| i16::from_le_bytes([pair[0], pair[1]])),
                );
                act.encode_buf.clear();
                encoder.encode(&act.sample_buf, &mut act.encode_buf);
                if act.encode_buf.is_empty() {
                    false
                } else {
                    (act.sink)(std::mem::take(&mut act.encode_buf));
                    true
                }
            }
        }
    }

    fn enqueue_svc(&self, messages: Vec<SvcMessage>) -> ironrdp_pdu::PduResult<()> {
        if messages.is_empty() {
            return Ok(());
        }
        let mut q = self
            .outbound_svc
            .lock()
            .map_err(|_| pdu_other_err!("rdpeai::uplink", "outbound queue poisoned"))?;
        if q.len() >= MAX_OUTBOUND_BATCHES {
            // Drop oldest audio packet rather than stalling capture on network jitter.
            q.pop_front();
        }
        q.push_back(messages);
        Ok(())
    }

    fn drain_outbound(&self) -> Vec<Vec<SvcMessage>> {
        if let Ok(mut q) = self.outbound_svc.lock() {
            q.drain(..).collect()
        } else {
            Vec::new()
        }
    }
}

pub(crate) struct MicrophoneSessionGuard<'a> {
    pub(crate) mic_state: &'a SharedMicrophoneState,
    pub(crate) session_state: &'a RwLock<SessionState>,
}

impl Drop for MicrophoneSessionGuard<'_> {
    fn drop(&mut self) {
        self.mic_state.shutdown_all(self.session_state);
    }
}

struct HavenRdpeaiHandler {
    session_state: Arc<RwLock<SessionState>>,
    mic_state: Arc<SharedMicrophoneState>,
}

impl HavenRdpeaiHandler {
    fn callback(&self) -> Option<Arc<dyn MicrophoneCallback>> {
        self.session_state
            .read()
            .ok()
            .and_then(|s| s.mic_callback.clone())
    }

    fn build_encoder(encode_format: &AudioFormat) -> Result<Option<AudioEncoder>, ()> {
        if encode_format.format == WaveFormat::PCM && encode_format.bits_per_sample == 16 {
            Ok(None)
        } else {
            AudioEncoder::new(encode_format).map(Some).map_err(|_| ())
        }
    }
}

impl RdpeaiCaptureHandler for HavenRdpeaiHandler {
    fn supported_formats(&self) -> &[AudioFormat] {
        &SUPPORTED_CAPTURE_FORMATS
    }

    fn choose_formats(&self, server_formats: &[AudioFormat]) -> Vec<AudioFormat> {
        let chosen = select_preferred_input_formats(server_formats);
        info!(
            "RDPEAI format negotiation: server offered {}, selected {} format(s)",
            server_formats.len(),
            chosen.len()
        );
        chosen
    }

    fn open(
        &mut self,
        capture_format: &AudioFormat,
        encode_format: &AudioFormat,
        packet_size: usize,
        sink: AudioPacketSink,
    ) -> i32 {
        let cap_channels = usize::from(capture_format.n_channels.max(1));
        let frames_per_packet = u32::try_from(packet_size / (cap_channels * 2))
            .unwrap_or(0)
            .max(1);
        let encoder = match Self::build_encoder(encode_format) {
            Ok(enc) => enc,
            Err(()) => return OpenReplyPdu::E_FAIL,
        };
        let Some(cb) = self.callback() else {
            return OpenReplyPdu::E_FAIL;
        };
        let sample_rate = encode_format.n_samples_per_sec;
        let channels = encode_format.n_channels;
        if !cb.on_mic_open(sample_rate, channels, frames_per_packet) {
            warn!(
                "RDPEAI host microphone open declined ({}Hz {}ch {}fpp)",
                sample_rate, channels, frames_per_packet
            );
            return OpenReplyPdu::E_FAIL;
        }
        if let Ok(mut act) = self.mic_state.active.lock() {
            *act = Some(ActiveCapture {
                sink,
                encoder,
                sample_rate,
                channels,
                frames_per_packet,
                sample_buf: Vec::new(),
                encode_buf: Vec::new(),
            });
        }
        self.mic_state.capturing.store(true, Ordering::Release);
        info!(
            "RDPEAI capture started ({:?} {}Hz {}ch {}fpp)",
            encode_format.format, sample_rate, channels, frames_per_packet
        );
        OpenReplyPdu::S_OK
    }

    fn set_format(&mut self, encode_format: &AudioFormat, _packet_size: usize) -> bool {
        let encoder = match Self::build_encoder(encode_format) {
            Ok(enc) => enc,
            Err(()) => return false,
        };
        let restart_params = {
            let Ok(guard) = self.mic_state.active.lock() else {
                return false;
            };
            let Some(act) = guard.as_ref() else {
                return false;
            };
            if act.sample_rate != encode_format.n_samples_per_sec
                || act.channels != encode_format.n_channels
            {
                Some(act.frames_per_packet)
            } else {
                None
            }
        };

        if let Some(fpp) = restart_params {
            let Some(cb) = self.callback() else {
                return false;
            };
            if !cb.on_mic_open(
                encode_format.n_samples_per_sec,
                encode_format.n_channels,
                fpp,
            ) {
                return false;
            }
        }

        if let Ok(mut guard) = self.mic_state.active.lock() {
            if let Some(act) = guard.as_mut() {
                act.encoder = encoder;
                act.sample_rate = encode_format.n_samples_per_sec;
                act.channels = encode_format.n_channels;
                debug!(
                    "RDPEAI format switched to {:?} {}Hz {}ch",
                    encode_format.format, encode_format.n_samples_per_sec, encode_format.n_channels
                );
                return true;
            }
        }
        false
    }

    fn close(&mut self) {
        let was_active = if let Ok(mut act) = self.mic_state.active.lock() {
            act.take().is_some()
        } else {
            false
        };
        self.mic_state.capturing.store(false, Ordering::Release);
        if was_active {
            if let Some(cb) = self.callback() {
                cb.on_mic_close();
            }
            info!("RDPEAI capture closed");
        }
    }
}

pub(crate) fn attach_to_drdynvc(
    drdynvc: DrdynvcClient,
    session_state: Arc<RwLock<SessionState>>,
    mic_state: Arc<SharedMicrophoneState>,
) -> DrdynvcClient {
    let uplink_state = Arc::clone(&mic_state);
    let uplink: DvcUplink = Box::new(move |_channel_id, messages| {
        uplink_state.enqueue_svc(messages)
    });
    let handler = HavenRdpeaiHandler {
        session_state,
        mic_state,
    };
    drdynvc.with_dynamic_channel(RdpeaiClient::new(Box::new(handler), uplink))
}

pub(crate) fn flush_rdpeai(
    active_stage: &mut ironrdp_session::ActiveStage,
    mic_state: &SharedMicrophoneState,
) -> Vec<Vec<u8>> {
    let batches = mic_state.drain_outbound();
    if batches.is_empty() {
        return Vec::new();
    }
    let mut frames = Vec::with_capacity(batches.len());
    for msgs in batches {
        match active_stage.encode_dvc_messages(msgs) {
            Ok(bytes) => {
                if !bytes.is_empty() {
                    frames.push(bytes);
                }
            }
            Err(e) => {
                warn!("RDPEAI encode_dvc_messages failed: {e}");
            }
        }
    }
    frames
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicUsize;

    use ironrdp_core::encode_vec;
    use ironrdp_dvc::DvcProcessor;
    use ironrdp_rdpeai::pdu::{FormatsPdu, OpenPdu, RdpeaiPdu, Version, VersionPdu};

    use super::*;

    struct MockMicCb {
        opens: AtomicUsize,
        closes: AtomicUsize,
    }

    impl MicrophoneCallback for MockMicCb {
        fn on_mic_open(&self, _sample_rate: u32, _channels: u16, _frames_per_packet: u32) -> bool {
            self.opens.fetch_add(1, Ordering::SeqCst);
            true
        }

        fn on_mic_close(&self) {
            self.closes.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn select_preferred_input_formats_ranks_16bit_pcm_first() {
        let alaw = AudioFormat {
            format: WaveFormat::ALAW,
            n_channels: 1,
            n_samples_per_sec: 8_000,
            n_avg_bytes_per_sec: 8_000,
            n_block_align: 1,
            bits_per_sample: 8,
            data: None,
        };
        let pcm48 = pcm_format(1, 48_000, 16);
        let chosen = select_preferred_input_formats(&[alaw.clone(), pcm48.clone()]);
        assert_eq!(chosen, vec![pcm48, alaw]);
    }

    #[test]
    fn end_to_end_version_formats_open_and_pcm_push_queues_svc_packets() {
        let cb = Arc::new(MockMicCb {
            opens: AtomicUsize::new(0),
            closes: AtomicUsize::new(0),
        });
        let session_state = Arc::new(RwLock::new(SessionState {
            connected: true,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            audio_callback: None,
            drive_callback: None,
            camera_callback: None,
            mic_callback: Some(cb.clone()),
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }));
        let mic_state = Arc::new(SharedMicrophoneState::new());
        let uplink_st = Arc::clone(&mic_state);
        let uplink: DvcUplink = Box::new(move |_cid, msgs| uplink_st.enqueue_svc(msgs));
        let mut client = RdpeaiClient::new(
            Box::new(HavenRdpeaiHandler {
                session_state,
                mic_state: Arc::clone(&mic_state),
            }),
            uplink,
        );

        assert!(client.start(21).unwrap().is_empty());

        let ver = encode_vec(&RdpeaiPdu::Version(VersionPdu::new(Version::V2))).unwrap();
        assert_eq!(client.process(21, &ver).unwrap().len(), 1);

        let fmt48 = pcm_format(1, 48_000, 16);
        let fmts = encode_vec(&RdpeaiPdu::Formats(FormatsPdu::server(vec![fmt48.clone()]))).unwrap();
        assert_eq!(client.process(21, &fmts).unwrap().len(), 2);

        let open = encode_vec(&RdpeaiPdu::Open(OpenPdu {
            frames_per_packet: 480,
            initial_format: 0,
            capture_format: fmt48,
        }))
        .unwrap();
        let open_reply = client.process(21, &open).unwrap();
        assert_eq!(open_reply.len(), 2, "FormatChange + OpenReply(S_OK)");
        assert!(mic_state.is_capturing());
        assert_eq!(cb.opens.load(Ordering::SeqCst), 1);

        assert!(mic_state.push_pcm(vec![0u8; 960]));
        assert_eq!(mic_state.drain_outbound().len(), 1);

        client.close(21);
        assert!(!mic_state.is_capturing());
        assert_eq!(cb.closes.load(Ordering::SeqCst), 1);
    }
}
