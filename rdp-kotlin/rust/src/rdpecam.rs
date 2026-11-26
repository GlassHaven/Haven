//! MS-RDPECAM (`RDCamera_Device_Enumerator` + per-device dynamic virtual
//! channels) video capture redirection (#890).
//!
//! Raw camera frames and device identifiers beyond synthetic channel indices
//! are never written to logs (`#518` PII scrub policy).

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use ironrdp_dvc::DrdynvcClient;
use ironrdp_pdu::pdu_other_err;
use ironrdp_rdpecam::{
    CameraBackend, CameraRedirectionSession, DeviceDescriptor, ErrorCode, MediaFormat, MediaType,
    SampleDelivery, SampleSink, SampleUplink, StartStreamInfo, StreamDescription,
};
use ironrdp_svc::SvcMessage;
use log::{debug, info, warn};

use crate::{CameraCallback, RdpCameraDevice, RdpCameraFormat, RdpCameraMode, SessionState};

const MAX_REDIRECTED_CAMERAS: usize = 16;
const MAX_OUTBOUND_BATCHES: usize = 64;

struct CameraSlot {
    sink: Option<SampleSink>,
    streaming: bool,
}

pub(crate) struct SharedCameraState {
    slots: Mutex<HashMap<String, CameraSlot>>,
    outbound_svc: Mutex<VecDeque<Vec<SvcMessage>>>,
    any_streaming: AtomicBool,
}

impl SharedCameraState {
    pub(crate) fn new() -> Self {
        Self {
            slots: Mutex::new(HashMap::new()),
            outbound_svc: Mutex::new(VecDeque::new()),
            any_streaming: AtomicBool::new(false),
        }
    }

    pub(crate) fn reset_for_connection(&self) {
        if let Ok(mut slots) = self.slots.lock() {
            for slot in slots.values_mut() {
                slot.sink = None;
                slot.streaming = false;
            }
            slots.clear();
        }
        if let Ok(mut q) = self.outbound_svc.lock() {
            q.clear();
        }
        self.any_streaming.store(false, Ordering::Release);
    }

    pub(crate) fn shutdown_all(&self, session_state: &RwLock<SessionState>) {
        let active_ids: Vec<String> = if let Ok(mut slots) = self.slots.lock() {
            let ids = slots
                .iter()
                .filter(|(_, slot)| slot.streaming)
                .map(|(id, _)| id.clone())
                .collect();
            for slot in slots.values_mut() {
                slot.sink = None;
                slot.streaming = false;
            }
            ids
        } else {
            Vec::new()
        };
        self.any_streaming.store(false, Ordering::Release);
        if let Ok(mut q) = self.outbound_svc.lock() {
            q.clear();
        }
        if !active_ids.is_empty() {
            let cb = session_state
                .read()
                .ok()
                .and_then(|s| s.camera_callback.clone());
            if let Some(cb) = cb {
                for id in active_ids {
                    cb.stop_stream(id);
                }
            }
        }
    }

    pub(crate) fn has_active_stream(&self) -> bool {
        self.any_streaming.load(Ordering::Acquire)
    }

    pub(crate) fn has_pending_request(&self, camera_id: &str) -> bool {
        let Ok(slots) = self.slots.lock() else {
            return false;
        };
        slots
            .get(camera_id)
            .and_then(|slot| slot.sink.as_ref())
            .is_some_and(|sink| sink.is_active() && sink.pending_requests(0) > 0)
    }

    pub(crate) fn push_sample(&self, camera_id: &str, sample: Vec<u8>) -> bool {
        let sink = {
            let Ok(slots) = self.slots.lock() else {
                return false;
            };
            slots.get(camera_id).and_then(|slot| slot.sink.clone())
        };
        let Some(sink) = sink else {
            return false;
        };
        matches!(sink.send_sample(0, sample), SampleDelivery::Sent)
    }

    fn register_slot(&self, camera_id: String) {
        if let Ok(mut slots) = self.slots.lock() {
            slots.insert(
                camera_id,
                CameraSlot {
                    sink: None,
                    streaming: false,
                },
            );
            let any = slots.values().any(|s| s.streaming);
            self.any_streaming.store(any, Ordering::Release);
        }
    }

    fn set_active_sink(&self, camera_id: &str, sink: SampleSink) {
        if let Ok(mut slots) = self.slots.lock() {
            if let Some(slot) = slots.get_mut(camera_id) {
                slot.sink = Some(sink);
                slot.streaming = true;
            }
            let any = slots.values().any(|s| s.streaming);
            self.any_streaming.store(any, Ordering::Release);
        }
    }

    fn clear_active_sink(&self, camera_id: &str) -> bool {
        if let Ok(mut slots) = self.slots.lock() {
            let was_streaming = if let Some(slot) = slots.get_mut(camera_id) {
                slot.sink = None;
                let prev = slot.streaming;
                slot.streaming = false;
                prev
            } else {
                false
            };
            let any = slots.values().any(|s| s.streaming);
            self.any_streaming.store(any, Ordering::Release);
            was_streaming
        } else {
            false
        }
    }

    fn enqueue_svc(&self, messages: Vec<SvcMessage>) -> ironrdp_pdu::PduResult<()> {
        if messages.is_empty() {
            return Ok(());
        }
        let mut q = self
            .outbound_svc
            .lock()
            .map_err(|_| pdu_other_err!("rdpecam::uplink", "outbound queue poisoned"))?;
        if q.len() >= MAX_OUTBOUND_BATCHES {
            return Err(pdu_other_err!(
                "rdpecam::uplink",
                "camera outbound queue full"
            ));
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

pub(crate) struct CameraSessionGuard<'a> {
    pub(crate) camera_state: &'a SharedCameraState,
    pub(crate) session_state: &'a RwLock<SessionState>,
}

impl Drop for CameraSessionGuard<'_> {
    fn drop(&mut self) {
        self.camera_state.shutdown_all(self.session_state);
    }
}

fn to_media_type(mode: &RdpCameraMode) -> Option<MediaType> {
    let (format, flags) = match mode.format {
        RdpCameraFormat::Mjpg => (MediaFormat::Mjpg, MediaType::DECODING_REQUIRED),
        RdpCameraFormat::Nv12 => (MediaFormat::Nv12, 0),
    };
    let fps = mode.fps.clamp(1, 120);
    MediaType::new(format, mode.width, mode.height, fps, 1, 1, 1, flags).ok()
}

fn from_media_type(mt: &MediaType) -> Option<RdpCameraMode> {
    let format = match mt.format {
        MediaFormat::Mjpg => RdpCameraFormat::Mjpg,
        MediaFormat::Nv12 => RdpCameraFormat::Nv12,
        _ => return None,
    };
    let fps = mt
        .frame_rate_numerator
        .checked_div(mt.frame_rate_denominator.max(1))
        .unwrap_or(30)
        .clamp(1, 120);
    Some(RdpCameraMode {
        format,
        width: mt.width,
        height: mt.height,
        fps,
    })
}

struct HavenCameraBackend {
    camera_id: String,
    supported_types: Vec<MediaType>,
    current_type: MediaType,
    session_state: Arc<RwLock<SessionState>>,
    camera_state: Arc<SharedCameraState>,
}

impl HavenCameraBackend {
    fn callback(&self) -> Option<Arc<dyn CameraCallback>> {
        self.session_state
            .read()
            .ok()
            .and_then(|s| s.camera_callback.clone())
    }
}

impl CameraBackend for HavenCameraBackend {
    fn activate(&mut self) -> Result<(), ErrorCode> {
        debug!("RDPECAM activating camera backend");
        Ok(())
    }

    fn deactivate(&mut self) -> Result<(), ErrorCode> {
        debug!("RDPECAM deactivating camera backend");
        if self.camera_state.clear_active_sink(&self.camera_id) {
            if let Some(cb) = self.callback() {
                cb.stop_stream(self.camera_id.clone());
            }
        }
        Ok(())
    }

    fn streams(&mut self) -> Result<Vec<StreamDescription>, ErrorCode> {
        Ok(vec![StreamDescription::color(true, false)])
    }

    fn media_types(&mut self, stream_index: u8) -> Result<Vec<MediaType>, ErrorCode> {
        if stream_index != 0 {
            return Err(ErrorCode::InvalidStreamNumber);
        }
        Ok(self.supported_types.clone())
    }

    fn current_media_type(&mut self, stream_index: u8) -> Result<MediaType, ErrorCode> {
        if stream_index != 0 {
            return Err(ErrorCode::InvalidStreamNumber);
        }
        Ok(self.current_type)
    }

    fn start_streams(
        &mut self,
        streams: &[StartStreamInfo],
        sink: SampleSink,
    ) -> Result<(), ErrorCode> {
        let Some(info) = streams.first() else {
            return Err(ErrorCode::InvalidRequest);
        };
        if info.stream_index != 0 {
            return Err(ErrorCode::InvalidStreamNumber);
        }
        let Some(mode) = from_media_type(&info.media_type) else {
            return Err(ErrorCode::InvalidMediaType);
        };
        let Some(cb) = self.callback() else {
            return Err(ErrorCode::NotInitialized);
        };
        if !cb.start_stream(self.camera_id.clone(), mode) {
            warn!(
                "RDPECAM host camera start declined ({}x{} @ {}fps)",
                mode.width, mode.height, mode.fps
            );
            return Err(ErrorCode::Unexpected);
        }
        self.current_type = info.media_type;
        self.camera_state.set_active_sink(&self.camera_id, sink);
        info!(
            "RDPECAM stream started ({:?} {}x{} @ {}fps)",
            mode.format, mode.width, mode.height, mode.fps
        );
        Ok(())
    }

    fn stop_streams(&mut self) -> Result<(), ErrorCode> {
        if self.camera_state.clear_active_sink(&self.camera_id) {
            if let Some(cb) = self.callback() {
                cb.stop_stream(self.camera_id.clone());
            }
            info!("RDPECAM stream stopped");
        }
        Ok(())
    }

    fn shutdown(&mut self) {
        if self.camera_state.clear_active_sink(&self.camera_id) {
            if let Some(cb) = self.callback() {
                cb.stop_stream(self.camera_id.clone());
            }
            debug!("RDPECAM backend shutdown");
        }
    }
}

fn sanitize_device_name(raw: &str, fallback_index: usize) -> String {
    let trimmed: String = raw
        .chars()
        .filter(|&c| c != '\0' && !c.is_control())
        .take(120)
        .collect();
    let trimmed = trimmed.trim();
    if trimmed.is_empty() {
        format!("Haven Camera {}", fallback_index + 1)
    } else {
        trimmed.to_owned()
    }
}

pub(crate) fn attach_to_drdynvc(
    mut drdynvc: DrdynvcClient,
    session_state: Arc<RwLock<SessionState>>,
    camera_state: Arc<SharedCameraState>,
) -> DrdynvcClient {
    let Some(cb) = session_state
        .read()
        .ok()
        .and_then(|s| s.camera_callback.clone())
    else {
        return drdynvc;
    };

    let raw_devices: Vec<RdpCameraDevice> = cb.list_cameras();
    if raw_devices.is_empty() {
        debug!("RDPECAM enabled but host reported 0 cameras");
        return drdynvc;
    }

    let session = CameraRedirectionSession::new();
    let uplink_state = Arc::clone(&camera_state);
    let uplink: SampleUplink = Arc::new(move |_channel_id, messages| {
        uplink_state.enqueue_svc(messages)
    });

    let mut advertised_devices = Vec::new();
    let mut prepared_listeners = Vec::new();

    for (idx, cam) in raw_devices.into_iter().take(MAX_REDIRECTED_CAMERAS).enumerate() {
        if cam.id.trim().is_empty() {
            continue;
        }
        let media_types: Vec<MediaType> = cam.modes.iter().filter_map(to_media_type).collect();
        let Some(&initial_type) = media_types.first() else {
            continue;
        };
        let channel_name = format!("RDCamera_Device_{idx}");
        let display_name = sanitize_device_name(&cam.name, idx);
        let descriptor = match DeviceDescriptor::new(display_name, channel_name) {
            Ok(d) => d,
            Err(e) => {
                warn!("RDPECAM skipping invalid camera descriptor #{idx}: {e}");
                continue;
            }
        };
        let redirected = match session.select_device(descriptor) {
            Ok(dev) => dev,
            Err(e) => {
                warn!("RDPECAM select_device failed for #{idx}: {e}");
                continue;
            }
        };

        camera_state.register_slot(cam.id.clone());
        let cam_id = cam.id;
        let st_clone = Arc::clone(&session_state);
        let cam_st_clone = Arc::clone(&camera_state);
        let listener = redirected.listener(
            move || {
                Some(HavenCameraBackend {
                    camera_id: cam_id.clone(),
                    supported_types: media_types.clone(),
                    current_type: initial_type,
                    session_state: Arc::clone(&st_clone),
                    camera_state: Arc::clone(&cam_st_clone),
                })
            },
            Arc::clone(&uplink),
        );
        advertised_devices.push(redirected);
        prepared_listeners.push(listener);
    }

    if advertised_devices.is_empty() {
        return drdynvc;
    }

    let count = advertised_devices.len();
    match session.enumeration_client(advertised_devices) {
        Ok(enum_client) => {
            drdynvc = drdynvc.with_dynamic_channel(enum_client);
            for listener in prepared_listeners {
                drdynvc = drdynvc.with_listener(listener);
            }
            info!("RDPECAM registered {count} camera device(s)");
        }
        Err(e) => {
            warn!("RDPECAM enumeration_client setup failed: {e}");
        }
    }
    drdynvc
}

/// Encode and drain any camera `SampleResponse` DVC messages queued by the
/// capture thread since the last session loop tick.
pub(crate) fn flush_rdpecam(
    active_stage: &mut ironrdp_session::ActiveStage,
    camera_state: &SharedCameraState,
) -> Vec<Vec<u8>> {
    let batches = camera_state.drain_outbound();
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
                warn!("RDPECAM encode_dvc_messages failed: {e}");
            }
        }
    }
    frames
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicUsize;

    use ironrdp_core::encode_vec;
    use ironrdp_dvc::{DvcChannelListener, DvcProcessor};
    use ironrdp_rdpecam::pdu::{DeviceMessage, DevicePdu, EnumerationPdu, ProtocolVersion};

    use super::*;

    struct MockCameraCb {
        starts: AtomicUsize,
        stops: AtomicUsize,
    }

    impl CameraCallback for MockCameraCb {
        fn list_cameras(&self) -> Vec<RdpCameraDevice> {
            vec![RdpCameraDevice {
                id: "0".into(),
                name: "Haven Back Camera".into(),
                modes: vec![
                    RdpCameraMode {
                        format: RdpCameraFormat::Mjpg,
                        width: 640,
                        height: 480,
                        fps: 30,
                    },
                    RdpCameraMode {
                        format: RdpCameraFormat::Nv12,
                        width: 320,
                        height: 240,
                        fps: 15,
                    },
                ],
            }]
        }

        fn start_stream(&self, _camera_id: String, _mode: RdpCameraMode) -> bool {
            self.starts.fetch_add(1, Ordering::SeqCst);
            true
        }

        fn stop_stream(&self, _camera_id: String) {
            self.stops.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn media_type_roundtrip_preserves_mjpg_and_nv12() {
        let mjpg = RdpCameraMode {
            format: RdpCameraFormat::Mjpg,
            width: 1280,
            height: 720,
            fps: 30,
        };
        let mt_mjpg = to_media_type(&mjpg).expect("valid mjpg media type");
        assert_eq!(mt_mjpg.flags, MediaType::DECODING_REQUIRED);
        assert_eq!(from_media_type(&mt_mjpg), Some(mjpg));

        let nv12 = RdpCameraMode {
            format: RdpCameraFormat::Nv12,
            width: 640,
            height: 480,
            fps: 15,
        };
        let mt_nv12 = to_media_type(&nv12).expect("valid nv12 media type");
        assert_eq!(mt_nv12.flags, 0);
        assert_eq!(from_media_type(&mt_nv12), Some(nv12));
    }

    #[test]
    fn end_to_end_negotiation_sample_request_and_push_sample_queues_svc_frame() {
        let mock_cb = Arc::new(MockCameraCb {
            starts: AtomicUsize::new(0),
            stops: AtomicUsize::new(0),
        });
        let session_state = Arc::new(RwLock::new(SessionState {
            connected: true,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            audio_callback: None,
            drive_callback: None,
            camera_callback: Some(mock_cb.clone()),
            mic_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }));
        let camera_state = Arc::new(SharedCameraState::new());
        camera_state.register_slot("0".into());

        let session = CameraRedirectionSession::new();
        let desc = DeviceDescriptor::new("Haven Back Camera".into(), "RDCamera_Device_0".into())
            .unwrap();
        let redirected = session.select_device(desc).unwrap();

        let uplink_state = Arc::clone(&camera_state);
        let uplink: SampleUplink = Arc::new(move |_cid, msgs| uplink_state.enqueue_svc(msgs));

        let media_types: Vec<MediaType> = mock_cb.list_cameras()[0]
            .modes
            .iter()
            .filter_map(to_media_type)
            .collect();
        let initial = media_types[0];
        let st_clone = Arc::clone(&session_state);
        let cam_st_clone = Arc::clone(&camera_state);
        let mut listener = redirected.listener(
            move || {
                Some(HavenCameraBackend {
                    camera_id: "0".into(),
                    supported_types: media_types.clone(),
                    current_type: initial,
                    session_state: Arc::clone(&st_clone),
                    camera_state: Arc::clone(&cam_st_clone),
                })
            },
            uplink,
        );

        let mut enum_client = session.enumeration_client(vec![redirected]).unwrap();
        let start_msgs = enum_client.start(10).unwrap();
        assert_eq!(start_msgs.len(), 1);
        let ver_resp = encode_vec(&EnumerationPdu::SelectVersionResponse(ProtocolVersion::V2)).unwrap();
        let added = enum_client.process(10, &ver_resp).unwrap();
        assert_eq!(added.len(), 1, "expected 1 DeviceAdded notification");

        let mut dev_proc = listener.create(11).expect("device channel should open after advertise");
        assert!(dev_proc.start(11).unwrap().is_empty());

        // Activate -> StartStreams(MJPG 640x480@30) -> SampleRequest(0)
        let activate = encode_vec(&DeviceMessage::new(
            ProtocolVersion::V2,
            DevicePdu::ActivateDeviceRequest,
        ))
        .unwrap();
        assert_eq!(dev_proc.process(11, &activate).unwrap().len(), 1);

        let start_req = encode_vec(&DeviceMessage::new(
            ProtocolVersion::V2,
            DevicePdu::StartStreamsRequest(vec![StartStreamInfo {
                stream_index: 0,
                media_type: initial,
            }]),
        ))
        .unwrap();
        assert_eq!(dev_proc.process(11, &start_req).unwrap().len(), 1);
        assert!(camera_state.has_active_stream());
        assert_eq!(mock_cb.starts.load(Ordering::SeqCst), 1);
        assert!(!camera_state.has_pending_request("0"));

        let sample_req = encode_vec(&DeviceMessage::new(
            ProtocolVersion::V2,
            DevicePdu::SampleRequest(0),
        ))
        .unwrap();
        assert!(dev_proc.process(11, &sample_req).unwrap().is_empty());
        assert!(camera_state.has_pending_request("0"));

        // Push a valid JPEG SOI+EOI sample (`0xFF, 0xD8, 0xFF, 0xD9`).
        assert!(camera_state.push_sample("0", vec![0xFF, 0xD8, 0xFF, 0xD9]));
        assert!(!camera_state.has_pending_request("0"));
        assert_eq!(camera_state.drain_outbound().len(), 1);

        // Closing the channel stops the stream cleanly.
        dev_proc.close(11);
        assert!(!camera_state.has_active_stream());
        assert_eq!(mock_cb.stops.load(Ordering::SeqCst), 1);
    }
}
