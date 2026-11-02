//! MS-RDPEI Input Virtual Channel Extension client over DRDYNVC (#778).
//!
//! Channel name: `"Microsoft::Windows::RDS::Input"`.
//!
//! [MS-RDPEI]: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpei/72a8cb65-7f6c-407c-a21a-3d970721fed0

pub mod contact;
pub mod pdu;
pub mod varint;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use ironrdp_core::{decode, impl_as_any, EncodeResult};
use ironrdp_dvc::{encode_dvc_messages, DvcClientProcessor, DvcMessage, DvcProcessor};
use ironrdp_pdu::{decode_err, PduResult};
use ironrdp_svc::{ChannelFlags, SvcMessage};
use log::{debug, info};

use self::pdu::{
    CsReadyFlags, CsReadyPdu, RdpInputProtocolVersion, RdpeiPdu, ScReadyFeatures, TouchEventPdu,
};

pub const CHANNEL_NAME: &str = "Microsoft::Windows::RDS::Input";
pub const DEFAULT_MAX_TOUCH_CONTACTS: u16 = 10;

/// Client endpoint for the MS-RDPEI dynamic virtual channel.
#[derive(Debug)]
pub struct RdpeiClient {
    max_touch_contacts: u16,
    cs_ready_flags: CsReadyFlags,
    advertise_version: RdpInputProtocolVersion,
    ready: bool,
    suspended: bool,
    negotiated_version: Option<RdpInputProtocolVersion>,
    server_features: Option<ScReadyFeatures>,
    /// Shared flag allowing the input producer to check lock-free whether
    /// RDPEI is active (ready and not suspended) or needs mouse fallback.
    active_flag: Arc<AtomicBool>,
}

impl RdpeiClient {
    pub fn new(active_flag: Arc<AtomicBool>) -> Self {
        Self {
            max_touch_contacts: DEFAULT_MAX_TOUCH_CONTACTS,
            cs_ready_flags: CsReadyFlags::SHOW_TOUCH_VISUALS,
            advertise_version: RdpInputProtocolVersion::V300,
            ready: false,
            suspended: false,
            negotiated_version: None,
            server_features: None,
            active_flag,
        }
    }

    pub fn encode_touch_event(&self, channel_id: u32, event: TouchEventPdu) -> EncodeResult<Vec<SvcMessage>> {
        if !self.ready {
            return Err(ironrdp_core::other_err!("RdpeiClient", "rdpei channel is not ready"));
        }
        if self.suspended {
            return Err(ironrdp_core::other_err!("RdpeiClient", "rdpei input is suspended"));
        }
        encode_dvc_messages(
            channel_id,
            vec![Box::new(RdpeiPdu::Touch(event))],
            ChannelFlags::empty(),
        )
    }

    fn build_cs_ready(&self, server_version: RdpInputProtocolVersion, server_features: ScReadyFeatures) -> CsReadyPdu {
        let version = core::cmp::min(self.advertise_version, server_version);
        let mut flags = self.cs_ready_flags;
        if server_version == RdpInputProtocolVersion::V100 {
            flags.remove(CsReadyFlags::DISABLE_TIMESTAMP_INJECTION);
        }
        if version < RdpInputProtocolVersion::V300
            || !server_features.contains(ScReadyFeatures::MULTIPEN_INJECTION_SUPPORTED)
        {
            flags.remove(CsReadyFlags::ENABLE_MULTIPEN_INJECTION);
        }
        CsReadyPdu::new(flags, version, self.max_touch_contacts)
    }

    fn sync_active_flag(&self) {
        self.active_flag.store(self.ready && !self.suspended, Ordering::Release);
    }

    fn reset(&mut self) {
        self.ready = false;
        self.suspended = false;
        self.negotiated_version = None;
        self.server_features = None;
        self.sync_active_flag();
    }
}

impl_as_any!(RdpeiClient);

impl DvcProcessor for RdpeiClient {
    fn channel_name(&self) -> &str {
        CHANNEL_NAME
    }

    fn start(&mut self, channel_id: u32) -> PduResult<Vec<DvcMessage>> {
        debug!("RDPEI channel opened (id={channel_id}), awaiting SC_READY");
        self.reset();
        Ok(Vec::new())
    }

    fn close(&mut self, channel_id: u32) {
        debug!("RDPEI channel closed (id={channel_id})");
        self.reset();
    }

    fn process(&mut self, _channel_id: u32, payload: &[u8]) -> PduResult<Vec<DvcMessage>> {
        let pdu: RdpeiPdu = decode(payload).map_err(|e| decode_err!(e))?;

        match pdu {
            RdpeiPdu::ScReady(sc) => {
                let cs = self.build_cs_ready(
                    sc.protocol_version,
                    sc.supported_features.unwrap_or(ScReadyFeatures::empty()),
                );
                self.server_features = sc.supported_features;
                self.suspended = false;
                self.negotiated_version = Some(core::cmp::min(sc.protocol_version, cs.protocol_version));
                self.ready = true;
                self.sync_active_flag();
                info!(
                    "RDPEI negotiated version={:?} max_contacts={} flags={:?}",
                    self.negotiated_version, cs.max_touch_contacts, cs.flags
                );
                Ok(vec![Box::new(RdpeiPdu::CsReady(cs))])
            }
            RdpeiPdu::SuspendInput => {
                debug!("RDPEI input suspended by server");
                self.suspended = true;
                self.sync_active_flag();
                Ok(Vec::new())
            }
            RdpeiPdu::ResumeInput => {
                debug!("RDPEI input resumed by server");
                self.suspended = false;
                self.sync_active_flag();
                Ok(Vec::new())
            }
            RdpeiPdu::CsReady(_) | RdpeiPdu::Touch(_) => {
                debug!("Ignoring unexpected client-to-server RDPEI PDU from server");
                Ok(Vec::new())
            }
        }
    }
}

impl DvcClientProcessor for RdpeiClient {}

#[cfg(test)]
mod tests {
    use ironrdp_core::encode_vec;

    use super::pdu::{HeaderOnlyPdu, ScReadyPdu, TouchContact, TouchContactFlags, TouchFrame};
    use super::*;

    #[test]
    fn sc_ready_activates_client_and_suspend_pauses_it() {
        let active = Arc::new(AtomicBool::new(false));
        let mut client = RdpeiClient::new(Arc::clone(&active));

        client.start(7).unwrap();
        assert!(!active.load(Ordering::Acquire));

        let sc = encode_vec(&ScReadyPdu::new(RdpInputProtocolVersion::V200)).unwrap();
        let reply = client.process(7, &sc).unwrap();
        assert_eq!(reply.len(), 1);
        assert!(active.load(Ordering::Acquire));

        let frame = TouchFrame::new(
            0,
            vec![TouchContact::new(
                0,
                320,
                240,
                TouchContactFlags::DOWN | TouchContactFlags::INRANGE | TouchContactFlags::INCONTACT,
            )],
        );
        let msgs = client.encode_touch_event(7, TouchEventPdu::new(0, vec![frame])).unwrap();
        assert!(!msgs.is_empty());

        let suspend = encode_vec(&HeaderOnlyPdu::suspend()).unwrap();
        client.process(7, &suspend).unwrap();
        assert!(!active.load(Ordering::Acquire));
        assert!(client.encode_touch_event(7, TouchEventPdu::new(0, Vec::new())).is_err());

        let resume = encode_vec(&HeaderOnlyPdu::resume()).unwrap();
        client.process(7, &resume).unwrap();
        assert!(active.load(Ordering::Acquire));
    }
}

