//! MS-RDPEA (`rdpsnd` static channel + `AUDIO_PLAYBACK_DVC` dynamic channel)
//! audio output and `INFO_REMOTECONSOLEAUDIO` ClientInfo flag glue (#890).
//!
//! Decodes negotiated wave streams (16-bit/8-bit PCM, IMA/Microsoft ADPCM,
//! G.711 A-law/µ-law) into interleaved signed 16-bit little-endian PCM via
//! [`ironrdp_rdpsnd::codec::AudioDecoder`] and forwards them to Kotlin's
//! non-blocking `AudioTrack` sink through [`crate::AudioCallback`].

use std::borrow::Cow;
use std::io::{self, Read, Write};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::{Duration, Instant};

use ironrdp_core::{decode, encode_vec};
use ironrdp_pdu::mcs;
use ironrdp_pdu::rdp::client_info::ClientInfoFlags;
use ironrdp_pdu::rdp::headers::BasicSecurityHeaderFlags;
use ironrdp_pdu::rdp::ClientInfoPdu;
use ironrdp_pdu::x224::X224;
use ironrdp_rdpsnd::client::{Clock, Rdpsnd, RdpsndClientHandler, RdpsndDvcState};
use ironrdp_rdpsnd::codec::AudioDecoder;
use ironrdp_rdpsnd::pdu::{AudioFormat, AudioFormatFlags, QualityMode, VolumePdu, WaveFormat};
use log::{debug, info, warn};

/// Nominal Android `AudioTrack` ring-buffer latency added to `WaveConfirmPdu`
/// timestamps per MS-RDPEA 3.2.5.2.1.6 so the server paces audio emission.
const NOMINAL_PLAYBACK_LATENCY: Duration = Duration::from_millis(40);

static FALLBACK_FORMATS: &[AudioFormat] = &[
    AudioFormat {
        format: WaveFormat::PCM,
        n_channels: 2,
        n_samples_per_sec: 48_000,
        n_avg_bytes_per_sec: 48_000 * 4,
        n_block_align: 4,
        bits_per_sample: 16,
        data: None,
    },
    AudioFormat {
        format: WaveFormat::PCM,
        n_channels: 2,
        n_samples_per_sec: 44_100,
        n_avg_bytes_per_sec: 44_100 * 4,
        n_block_align: 4,
        bits_per_sample: 16,
        data: None,
    },
    AudioFormat {
        format: WaveFormat::PCM,
        n_channels: 2,
        n_samples_per_sec: 22_050,
        n_avg_bytes_per_sec: 22_050 * 4,
        n_block_align: 4,
        bits_per_sample: 16,
        data: None,
    },
    AudioFormat {
        format: WaveFormat::PCM,
        n_channels: 1,
        n_samples_per_sec: 44_100,
        n_avg_bytes_per_sec: 44_100 * 2,
        n_block_align: 2,
        bits_per_sample: 16,
        data: None,
    },
];

fn format_rank(fmt: &AudioFormat) -> u8 {
    match fmt.format {
        WaveFormat::PCM if fmt.bits_per_sample == 16 => {
            if fmt.n_channels == 2 && (fmt.n_samples_per_sec == 48_000 || fmt.n_samples_per_sec == 44_100) {
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

fn is_playable_on_android(fmt: &AudioFormat) -> bool {
    (1..=2).contains(&fmt.n_channels)
        && (8_000..=96_000).contains(&fmt.n_samples_per_sec)
        && AudioDecoder::supports(fmt)
}

/// Select and rank supported formats from the server's offered list.
pub(crate) fn select_preferred_formats(server_formats: &[AudioFormat]) -> Vec<AudioFormat> {
    let mut chosen: Vec<AudioFormat> = server_formats
        .iter()
        .filter(|fmt| is_playable_on_android(fmt))
        .cloned()
        .collect();
    chosen.sort_by_key(format_rank);
    chosen
}

pub(crate) struct HavenRdpsndHandler {
    state: Arc<RwLock<crate::SessionState>>,
    cached_decoder: Option<(AudioFormat, AudioDecoder)>,
    decode_buf: Vec<i16>,
}

impl core::fmt::Debug for HavenRdpsndHandler {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("HavenRdpsndHandler")
            .field(
                "cached_format",
                &self.cached_decoder.as_ref().map(|(fmt, _)| fmt),
            )
            .finish_non_exhaustive()
    }
}

impl HavenRdpsndHandler {
    pub(crate) fn new(state: Arc<RwLock<crate::SessionState>>) -> Self {
        Self {
            state,
            cached_decoder: None,
            decode_buf: Vec::new(),
        }
    }

    fn callback(&self) -> Option<Arc<dyn crate::AudioCallback>> {
        self.state.read().ok().and_then(|s| s.audio_callback.clone())
    }
}

impl RdpsndClientHandler for HavenRdpsndHandler {
    fn get_flags(&self) -> AudioFormatFlags {
        AudioFormatFlags::VOLUME
    }

    fn get_formats(&self) -> &[AudioFormat] {
        FALLBACK_FORMATS
    }

    fn choose_formats(&self, server_formats: &[AudioFormat]) -> Vec<AudioFormat> {
        let chosen = select_preferred_formats(server_formats);
        info!(
            "RDPSND format negotiation: server offered {}, selected {} format(s)",
            server_formats.len(),
            chosen.len()
        );
        chosen
    }

    fn wave(&mut self, format: &AudioFormat, _ts: u32, data: Cow<'_, [u8]>) {
        if data.is_empty() {
            return;
        }
        let Some(cb) = self.callback() else {
            return;
        };

        let needs_rebuild = match &self.cached_decoder {
            Some((cached_fmt, _)) => cached_fmt != format,
            None => true,
        };
        if needs_rebuild {
            match AudioDecoder::new(format) {
                Ok(dec) => {
                    debug!(
                        "RDPSND initialized decoder: format={} rate={}Hz channels={}",
                        format.format, format.n_samples_per_sec, format.n_channels
                    );
                    self.cached_decoder = Some((format.clone(), dec));
                }
                Err(e) => {
                    warn!("RDPSND unsupported wave format {:?}: {e}", format.format);
                    return;
                }
            }
        }

        let (_, decoder) = self.cached_decoder.as_mut().expect("populated above");
        self.decode_buf.clear();
        if let Err(e) = decoder.decode(&data, &mut self.decode_buf) {
            warn!("RDPSND wave decode failed: {e}");
            if self.decode_buf.is_empty() {
                return;
            }
        }
        if self.decode_buf.is_empty() {
            return;
        }

        let mut pcm_s16le = Vec::with_capacity(self.decode_buf.len() * 2);
        for &sample in &self.decode_buf {
            pcm_s16le.extend_from_slice(&sample.to_le_bytes());
        }
        cb.on_audio_pcm(pcm_s16le, format.n_samples_per_sec, format.n_channels);
    }

    fn playback_latency(&self) -> Duration {
        NOMINAL_PLAYBACK_LATENCY
    }

    fn set_volume(&mut self, volume: VolumePdu) {
        if let Some(cb) = self.callback() {
            let left = f32::from(volume.volume_left) / 65535.0;
            let right = f32::from(volume.volume_right) / 65535.0;
            cb.on_audio_volume(left, right);
        }
    }

    fn set_pitch(&mut self, _pitch: ironrdp_rdpsnd::pdu::PitchPdu) {}

    fn close(&mut self) {
        self.cached_decoder = None;
        if let Some(cb) = self.callback() {
            cb.on_audio_close();
        }
    }
}

fn monotonic_clock() -> Clock {
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    let epoch = *EPOCH.get_or_init(Instant::now);
    Box::new(move || epoch.elapsed())
}

pub(crate) fn new_static_client(state: Arc<RwLock<crate::SessionState>>) -> Rdpsnd {
    Rdpsnd::new(Box::new(HavenRdpsndHandler::new(state)))
        .with_quality_mode(QualityMode::High)
        .with_clock(monotonic_clock())
}

pub(crate) fn new_dvc_state(state: Arc<RwLock<crate::SessionState>>) -> RdpsndDvcState {
    RdpsndDvcState::new(Box::new(HavenRdpsndHandler::new(state)))
        .with_quality_mode(QualityMode::High)
        .with_clock(monotonic_clock())
}

/// If `frame` is an X.224 `SendDataRequest` carrying an RDP `ClientInfoPdu`
/// (`BasicSecurityHeaderFlags::INFO_PKT`), set `INFO_REMOTECONSOLEAUDIO`
/// (`0x00002000`) and clear `INFO_NOAUDIOPLAYBACK`, returning the re-encoded
/// X.224 frame. Returns `None` for any other handshake PDU.
pub(crate) fn patch_client_info_remote_console_audio(frame: &[u8]) -> Option<Vec<u8>> {
    let X224(sdr) = decode::<X224<mcs::SendDataRequest<'_>>>(frame).ok()?;
    let mut info_pdu = decode::<ClientInfoPdu>(sdr.user_data.as_ref()).ok()?;
    if !info_pdu
        .security_header
        .flags
        .contains(BasicSecurityHeaderFlags::INFO_PKT)
    {
        return None;
    }

    info_pdu
        .client_info
        .flags
        .insert(ClientInfoFlags::REMOTE_CONSOLE_AUDIO);
    info_pdu
        .client_info
        .flags
        .remove(ClientInfoFlags::NO_AUDIO_PLAYBACK);

    let user_data = encode_vec(&info_pdu).ok()?;
    let patched_sdr = mcs::SendDataRequest {
        initiator_id: sdr.initiator_id,
        channel_id: sdr.channel_id,
        user_data: Cow::Owned(user_data),
    };
    encode_vec(&X224(patched_sdr)).ok()
}

/// Handshake transport wrapper that optionally patches the single outgoing
/// `ClientInfoPdu` with `INFO_REMOTECONSOLEAUDIO` (`RdpAudioMode::PlayOnServer`),
/// then disarms itself for the remainder of `connect_finalize`.
pub(crate) struct HandshakeAudioStream<S> {
    inner: S,
    patch_remote_console_audio: bool,
}

impl<S> HandshakeAudioStream<S> {
    pub(crate) fn new(inner: S, patch_remote_console_audio: bool) -> Self {
        Self {
            inner,
            patch_remote_console_audio,
        }
    }

    pub(crate) fn into_inner(self) -> S {
        self.inner
    }
}

impl<S: Read> Read for HandshakeAudioStream<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.inner.read(buf)
    }
}

impl<S: Write> Write for HandshakeAudioStream<S> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.inner.write(buf)
    }

    fn write_all(&mut self, buf: &[u8]) -> io::Result<()> {
        if self.patch_remote_console_audio {
            if let Some(patched) = patch_client_info_remote_console_audio(buf) {
                self.patch_remote_console_audio = false;
                info!("Set INFO_REMOTECONSOLEAUDIO on outgoing ClientInfoPdu");
                return self.inner.write_all(&patched);
            }
        }
        self.inner.write_all(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ironrdp_dvc::DvcProcessor;
    use ironrdp_pdu::rdp::headers::BasicSecurityHeader;
    use ironrdp_rdpsnd::pdu::{
        ClientAudioOutputPdu, ServerAudioFormatPdu, ServerAudioOutputPdu, TrainingPdu, Version,
        Wave2Pdu,
    };
    use ironrdp_svc::SvcProcessor;
    use std::sync::Mutex;

    #[derive(Default)]
    struct RecordedAudio {
        pcm_chunks: Vec<(Vec<u8>, u32, u16)>,
        volumes: Vec<(f32, f32)>,
        closes: usize,
    }

    struct MockAudioCb(Mutex<RecordedAudio>);

    impl crate::AudioCallback for MockAudioCb {
        fn on_audio_pcm(&self, pcm_s16le: Vec<u8>, sample_rate: u32, channels: u16) {
            self.0
                .lock()
                .unwrap()
                .pcm_chunks
                .push((pcm_s16le, sample_rate, channels));
        }

        fn on_audio_volume(&self, left: f32, right: f32) {
            self.0.lock().unwrap().volumes.push((left, right));
        }

        fn on_audio_close(&self) {
            self.0.lock().unwrap().closes += 1;
        }
    }

    fn state_with_cb(cb: Arc<MockAudioCb>) -> Arc<RwLock<crate::SessionState>> {
        Arc::new(RwLock::new(crate::SessionState {
            connected: true,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            audio_callback: Some(cb),
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }))
    }

    #[test]
    fn choose_formats_filters_unsupported_and_ranks_16bit_pcm_first() {
        let aac = AudioFormat {
            format: WaveFormat::AAC_MS,
            n_channels: 2,
            n_samples_per_sec: 48_000,
            n_avg_bytes_per_sec: 24_000,
            n_block_align: 1,
            bits_per_sample: 0,
            data: Some(vec![0; 12]),
        };
        let surround_pcm = AudioFormat {
            format: WaveFormat::PCM,
            n_channels: 6,
            n_samples_per_sec: 48_000,
            n_avg_bytes_per_sec: 48_000 * 12,
            n_block_align: 12,
            bits_per_sample: 16,
            data: None,
        };
        let alaw = ironrdp_rdpsnd::codec::alaw_format(2, 22_050);
        let pcm16_44k = FALLBACK_FORMATS[1].clone();
        let pcm8 = AudioFormat {
            format: WaveFormat::PCM,
            n_channels: 2,
            n_samples_per_sec: 22_050,
            n_avg_bytes_per_sec: 44_100,
            n_block_align: 2,
            bits_per_sample: 8,
            data: None,
        };

        let chosen = select_preferred_formats(&[aac, surround_pcm, pcm8.clone(), alaw.clone(), pcm16_44k.clone()]);
        assert_eq!(chosen, vec![pcm16_44k, alaw, pcm8]);
    }

    #[test]
    fn static_rdpsnd_handshake_and_wave2_decode_deliver_s16le_pcm_and_volume() {
        let cb = Arc::new(MockAudioCb(Mutex::new(RecordedAudio::default())));
        let mut rdpsnd = new_static_client(state_with_cb(Arc::clone(&cb)));

        let srv_fmt = ServerAudioOutputPdu::AudioFormat(ServerAudioFormatPdu {
            version: Version::V8,
            formats: vec![FALLBACK_FORMATS[0].clone()],
        });
        let replies = rdpsnd
            .process(&encode_vec(&srv_fmt).expect("encode srv_fmt"))
            .expect("process srv_fmt");
        assert_eq!(replies.len(), 2, "expected ClientAudioFormat + QualityMode");

        let training = ServerAudioOutputPdu::Training(TrainingPdu {
            timestamp: 123,
            data: vec![0u8; 16],
        });
        let train_replies = rdpsnd
            .process(&encode_vec(&training).expect("encode training"))
            .expect("process training");
        assert_eq!(train_replies.len(), 1);
        let confirm = decode::<ClientAudioOutputPdu>(
            &train_replies[0].encode_unframed_pdu().expect("encode confirm"),
        )
        .expect("decode confirm");
        assert!(matches!(confirm, ClientAudioOutputPdu::TrainingConfirm(_)));

        // Send 2 stereo frames of 16-bit PCM: [1000, -1000, 2000, -2000].
        let samples: [i16; 4] = [1000, -1000, 2000, -2000];
        let raw: Vec<u8> = samples.iter().flat_map(|s| s.to_le_bytes()).collect();
        let wave2 = ServerAudioOutputPdu::Wave2(Wave2Pdu {
            timestamp: 500,
            format_no: 0,
            block_no: 7,
            audio_timestamp: 9000,
            data: Cow::Borrowed(&raw),
        });
        let wave_replies = rdpsnd
            .process(&encode_vec(&wave2).expect("encode wave2"))
            .expect("process wave2");
        assert_eq!(wave_replies.len(), 1);
        let wave_confirm = decode::<ClientAudioOutputPdu>(
            &wave_replies[0].encode_unframed_pdu().expect("encode wave confirm"),
        )
        .expect("decode wave confirm");
        assert!(matches!(
            wave_confirm,
            ClientAudioOutputPdu::WaveConfirm(ref c) if c.block_no == 7 && c.timestamp >= 540
        ));

        let vol = ServerAudioOutputPdu::Volume(VolumePdu {
            volume_left: 0x8000,
            volume_right: 0xFFFF,
        });
        let _ = rdpsnd.process(&encode_vec(&vol).unwrap()).unwrap();
        let _ = rdpsnd
            .process(&encode_vec(&ServerAudioOutputPdu::Close).unwrap())
            .unwrap();

        // Notice `begin_format_negotiation` calls `handler.close()` once on
        // format setup and `ServerAudioOutputPdu::Close` calls it once more.
        let rec = cb.0.lock().unwrap();
        assert_eq!(rec.pcm_chunks.len(), 1);
        assert_eq!(rec.pcm_chunks[0], (raw, 48_000, 2));
        assert_eq!(rec.volumes.len(), 1);
        assert!((rec.volumes[0].0 - (32768.0 / 65535.0)).abs() < 1e-4);
        assert!((rec.volumes[0].1 - 1.0).abs() < 1e-6);
        assert!(rec.closes >= 1);
    }

    #[test]
    fn dvc_rdpsnd_plays_reliable_and_lossy_wave2_blocks() {
        let cb = Arc::new(MockAudioCb(Mutex::new(RecordedAudio::default())));
        let dvc_state = new_dvc_state(state_with_cb(Arc::clone(&cb)));
        let mut reliable = dvc_state.channel();
        let mut lossy = dvc_state.lossy_channel();

        reliable.start(1).unwrap();
        lossy.start(2).unwrap();

        let srv_fmt = ServerAudioOutputPdu::AudioFormat(ServerAudioFormatPdu {
            version: Version::V8,
            formats: vec![FALLBACK_FORMATS[1].clone()],
        });
        let _ = reliable
            .process(1, &encode_vec(&srv_fmt).unwrap())
            .expect("dvc format");
        let training = ServerAudioOutputPdu::Training(TrainingPdu {
            timestamp: 10,
            data: Vec::new(),
        });
        let _ = reliable
            .process(1, &encode_vec(&training).unwrap())
            .expect("dvc training");

        let raw: Vec<u8> = [512i16, -512i16].iter().flat_map(|s| s.to_le_bytes()).collect();
        let wave2 = ServerAudioOutputPdu::Wave2(Wave2Pdu {
            timestamp: 20,
            format_no: 0,
            block_no: 1,
            audio_timestamp: 100,
            data: Cow::Borrowed(&raw),
        });
        let lossy_replies = lossy
            .process(2, &encode_vec(&wave2).unwrap())
            .expect("lossy wave2");
        assert!(lossy_replies.is_empty(), "lossy DVC does not confirm waves");

        let rec = cb.0.lock().unwrap();
        assert_eq!(rec.pcm_chunks, vec![(raw, 44_100, 2)]);
    }

    #[test]
    fn patch_client_info_sets_remote_console_audio_and_clears_no_audio_playback() {
        use ironrdp_pdu::rdp::client_info::{
            AddressFamily, ClientInfo, CompressionType, Credentials, ExtendedClientInfo,
            ExtendedClientOptionalInfo,
        };

        let pdu = ClientInfoPdu {
            security_header: BasicSecurityHeader {
                flags: BasicSecurityHeaderFlags::INFO_PKT,
            },
            client_info: ClientInfo {
                credentials: Credentials {
                    username: "alice".to_owned(),
                    password: "secret".to_owned(),
                    domain: None,
                },
                code_page: 0,
                flags: ClientInfoFlags::MOUSE | ClientInfoFlags::NO_AUDIO_PLAYBACK,
                compression_type: CompressionType::K8,
                alternate_shell: String::new(),
                work_dir: String::new(),
                extra_info: ExtendedClientInfo {
                    address_family: AddressFamily::INET,
                    address: "10.0.0.2".to_owned(),
                    dir: String::new(),
                    optional_data: ExtendedClientOptionalInfo::default(),
                },
                password_cookie: None,
            },
        };
        let raw_sdr = mcs::SendDataRequest {
            initiator_id: 1007,
            channel_id: 1003,
            user_data: Cow::Owned(encode_vec(&pdu).unwrap()),
        };
        let wire = encode_vec(&X224(raw_sdr)).unwrap();

        // Non-ClientInfo frames return None untouched.
        assert!(patch_client_info_remote_console_audio(&[0x03, 0x00, 0x00, 0x08, 0x02, 0xF0, 0x80, 0x00]).is_none());

        let patched = patch_client_info_remote_console_audio(&wire).expect("patched ClientInfoPdu");
        let X224(decoded_sdr) = decode::<X224<mcs::SendDataRequest<'_>>>(&patched).unwrap();
        assert_eq!(decoded_sdr.initiator_id, 1007);
        assert_eq!(decoded_sdr.channel_id, 1003);
        let decoded_info = decode::<ClientInfoPdu>(decoded_sdr.user_data.as_ref()).unwrap();
        assert!(decoded_info
            .client_info
            .flags
            .contains(ClientInfoFlags::REMOTE_CONSOLE_AUDIO));
        assert!(!decoded_info
            .client_info
            .flags
            .contains(ClientInfoFlags::NO_AUDIO_PLAYBACK));
        assert_eq!(decoded_info.client_info.credentials.username, "alice");
    }
}
