//! MS-RDPEL (`Microsoft::Windows::RDS::Location` dynamic virtual channel)
//! geographic location redirection (#890).
//!
//! Coordinates are never written to logs (`#518` PII scrub policy): only
//! channel lifecycle and status events are logged.

use std::sync::Mutex;

use ironrdp_rdpel::client::LocationClient;
use log::{debug, warn};

#[derive(Debug, Default)]
pub(crate) struct SharedLocationState {
    pending: Option<(f64, f64, i32)>,
}

impl SharedLocationState {
    pub(crate) fn new() -> Self {
        Self { pending: None }
    }

    pub(crate) fn reset_for_connection(&mut self) {
        self.pending = None;
    }

    /// Queue the latest geographic fix (`latitude` in `-90..=90`, `longitude`
    /// in `-180..=180`, `altitude_meters` in meters). Supersedes any older
    /// unsent fix waiting for `ServerReady`.
    pub(crate) fn queue_location(&mut self, latitude: f64, longitude: f64, altitude_meters: i32) {
        if !latitude.is_finite() || !longitude.is_finite() {
            return;
        }
        let clamped_lat = latitude.clamp(-90.0, 90.0);
        let clamped_lon = longitude.clamp(-180.0, 180.0);
        let clamped_alt = altitude_meters.clamp(-0x0FFF_FFFF, 0x0FFF_FFFF);
        self.pending = Some((clamped_lat, clamped_lon, clamped_alt));
    }

    fn peek_pending(&self) -> Option<(f64, f64, i32)> {
        self.pending
    }

    fn clear_if_matching(&mut self, sent: (f64, f64, i32)) {
        if self.pending == Some(sent) {
            self.pending = None;
        }
    }
}

pub(crate) fn new_dvc_client() -> LocationClient {
    LocationClient::new()
}

/// Flush any queued location fix once `Microsoft::Windows::RDS::Location` has
/// completed its `ServerReady -> ClientReady` handshake.
pub(crate) fn flush_rdpel(
    active_stage: &mut ironrdp_session::ActiveStage,
    state: &Mutex<SharedLocationState>,
) -> Vec<Vec<u8>> {
    let Some(fix) = state.lock().ok().and_then(|st| st.peek_pending()) else {
        return Vec::new();
    };

    let prepared_res = {
        let Some(dvc) = active_stage.get_dvc::<LocationClient>() else {
            return Vec::new();
        };
        if !dvc.processor().ready() {
            return Vec::new();
        }
        dvc.processor().prepare_location(fix.0, fix.1, fix.2)
    };

    let (prepared, msgs) = match prepared_res {
        Ok(pair) => pair,
        Err(e) => {
            warn!("RDPEL prepare_location failed: {e}");
            if let Ok(mut st) = state.lock() {
                st.clear_if_matching(fix);
            }
            return Vec::new();
        }
    };

    match active_stage.encode_dvc_messages(msgs) {
        Ok(bytes) => {
            if let Some(mut dvc) = active_stage.get_dvc_mut::<LocationClient>() {
                dvc.processor_mut().commit_location(prepared);
            }
            if let Ok(mut st) = state.lock() {
                st.clear_if_matching(fix);
            }
            debug!("RDPEL encoded location update ({} bytes)", bytes.len());
            if bytes.is_empty() {
                Vec::new()
            } else {
                vec![bytes]
            }
        }
        Err(e) => {
            warn!("RDPEL encode_dvc_messages failed: {e}");
            Vec::new()
        }
    }
}

#[cfg(test)]
mod tests {
    use ironrdp_core::encode_vec;
    use ironrdp_dvc::DvcProcessor;
    use ironrdp_rdpel::pdu::{LocationPdu, ReadyPdu};

    use super::*;

    #[test]
    fn shared_state_clamps_coordinates_and_ignores_nan() {
        let mut st = SharedLocationState::new();
        st.queue_location(f64::NAN, 10.0, 0);
        assert!(st.peek_pending().is_none());

        st.queue_location(95.0, -195.0, 42);
        assert_eq!(st.peek_pending(), Some((90.0, -180.0, 42)));

        st.clear_if_matching((90.0, -180.0, 42));
        assert!(st.peek_pending().is_none());
    }

    #[test]
    fn location_client_becomes_ready_on_server_ready_and_encodes_base_then_deltas() {
        let mut client = new_dvc_client();
        assert!(client.start(7).unwrap().is_empty());
        assert!(!client.ready());

        let server_ready = encode_vec(&LocationPdu::ServerReady(ReadyPdu::v1())).unwrap();
        let reply = client.process(7, &server_ready).unwrap();
        assert_eq!(reply.len(), 1, "expected ClientReady reply");
        assert!(client.ready());

        // First fix emits BaseLocation3dPdu; second at same altitude emits Location2dDeltaPdu;
        // third with new altitude emits Location3dDeltaPdu.
        let first = client.send_location(51.5074, -0.1278, 15).unwrap();
        assert!(!first.is_empty());
        let second = client.send_location(51.5075, -0.1279, 15).unwrap();
        assert!(!second.is_empty());
        let third = client.send_location(51.5076, -0.1280, 22).unwrap();
        assert!(!third.is_empty());
    }
}
