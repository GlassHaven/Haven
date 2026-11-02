//! Contact lifetime state machine and multi-touch frame builder ([MS-RDPEI] 3.1.1.1).
//!
//! [MS-RDPEI]: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpei/deb1ca39-344a-467a-b9d4-dfe196b46c9d

use super::pdu::{TouchContact, TouchContactFlags, TouchEventPdu, TouchFrame};

/// State of a contact the server knows about.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContactState {
    /// In range of the digitizer, not touching it.
    Hovering,
    /// Touching the digitizer.
    Engaged,
}

/// One local sample of a contact.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ContactSample {
    /// The digitizer still detects the contact.
    pub in_range: bool,
    /// The contact touches the digitizer.
    pub in_contact: bool,
    /// The local system canceled the contact.
    pub canceled: bool,
}

/// The wire flags for one sample and the state that follows it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ContactTransition {
    pub flags: TouchContactFlags,
    pub next: Option<ContactState>,
}

impl ContactTransition {
    /// Whether this sample takes the contact out of the engaged state.
    ///
    /// Such a contact is sent at its last engaged position ([MS-RDPEI] 3.1.1.1).
    #[must_use]
    pub fn keeps_position(self) -> bool {
        self.flags.contains(TouchContactFlags::UP)
    }
}

/// Advances a contact by one local sample ([MS-RDPEI] 3.1.1.1 "Touch Contact State Transitions").
#[must_use]
pub fn transition(previous: Option<ContactState>, sample: ContactSample) -> Option<ContactTransition> {
    use ContactState::{Engaged, Hovering};

    let transition = |flags, next| Some(ContactTransition { flags, next });

    if sample.canceled {
        return match previous {
            Some(Hovering) => transition(TouchContactFlags::UPDATE | TouchContactFlags::CANCELED, None),
            Some(Engaged) => transition(TouchContactFlags::UP | TouchContactFlags::CANCELED, None),
            None => None,
        };
    }

    let in_contact = sample.in_contact && sample.in_range;
    match (previous, sample.in_range, in_contact) {
        (None | Some(Hovering), true, true) => transition(
            TouchContactFlags::DOWN | TouchContactFlags::INRANGE | TouchContactFlags::INCONTACT,
            Some(Engaged),
        ),
        (Some(Engaged), true, true) => transition(
            TouchContactFlags::UPDATE | TouchContactFlags::INRANGE | TouchContactFlags::INCONTACT,
            Some(Engaged),
        ),
        (Some(Engaged), true, false) => transition(TouchContactFlags::UP | TouchContactFlags::INRANGE, Some(Hovering)),
        (Some(Engaged), false, _) => transition(TouchContactFlags::UP, None),
        (None | Some(Hovering), true, false) => {
            transition(TouchContactFlags::UPDATE | TouchContactFlags::INRANGE, Some(Hovering))
        }
        (Some(Hovering), false, _) => transition(TouchContactFlags::UPDATE, None),
        (None, false, _) => None,
    }
}

/// Phase of a touch point as reported by Android/Compose.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TouchPhase {
    Started,
    Moved,
    Ended,
    Canceled,
}

#[derive(Debug, Clone)]
struct TrackedTouch {
    external_id: u64,
    contact_id: u8,
    x: i32,
    y: i32,
    pressure: Option<u32>,
}

/// Groups per-contact touch events into RDPEI touch frames.
///
/// Every RDPEI frame lists all contacts currently touching the surface:
/// contacts that did not change in the frame are restated at their last position.
#[derive(Debug)]
pub struct TouchFrameBuilder {
    max_contacts: usize,
    tracked: Vec<TrackedTouch>,
    frames: Vec<TouchFrame>,
    current: Vec<TouchContact>,
    current_timestamp_us: Option<u64>,
    oldest_timestamp_us: Option<u64>,
    last_frame_timestamp_us: Option<u64>,
}

impl TouchFrameBuilder {
    #[must_use]
    pub fn new(max_contacts: u16) -> Self {
        Self {
            max_contacts: usize::from(max_contacts),
            tracked: Vec::new(),
            frames: Vec::new(),
            current: Vec::new(),
            current_timestamp_us: None,
            oldest_timestamp_us: None,
            last_frame_timestamp_us: None,
        }
    }

    pub fn push(
        &mut self,
        external_id: u64,
        phase: TouchPhase,
        x: i32,
        y: i32,
        pressure: Option<u32>,
        timestamp_us: u64,
    ) {
        let index = self.tracked.iter().position(|touch| touch.external_id == external_id);
        let (in_range, canceled) = match phase {
            TouchPhase::Started => (true, false),
            TouchPhase::Moved if index.is_some() => (true, false),
            TouchPhase::Moved => return,
            TouchPhase::Ended => (false, false),
            TouchPhase::Canceled => (false, true),
        };
        let sample = ContactSample {
            in_range,
            in_contact: in_range,
            canceled,
        };
        let Some(transition) = transition(index.map(|_| ContactState::Engaged), sample) else {
            return;
        };

        let contact_id = match index {
            Some(index) => self.tracked[index].contact_id,
            None => match self.allocate_contact_id() {
                Some(contact_id) => contact_id,
                None => return,
            },
        };
        if self.current.iter().any(|contact| contact.contact_id == contact_id) {
            self.finish_frame();
        }
        self.current_timestamp_us.get_or_insert(timestamp_us);

        let (x, y) = match index {
            Some(index) if transition.keeps_position() => (self.tracked[index].x, self.tracked[index].y),
            _ => (x, y),
        };
        let pressure = pressure.map(|pressure| pressure.min(1024));
        let mut contact = TouchContact::new(contact_id, x, y, transition.flags);
        if let Some(pressure) = pressure {
            contact = contact.with_pressure(pressure);
        }
        self.current.push(contact);

        match (transition.next, index) {
            (Some(_), Some(index)) => {
                let touch = &mut self.tracked[index];
                (touch.x, touch.y, touch.pressure) = (x, y, pressure);
            }
            (Some(_), None) => self.tracked.push(TrackedTouch {
                external_id,
                contact_id,
                x,
                y,
                pressure,
            }),
            (None, Some(index)) => {
                self.tracked.remove(index);
            }
            (None, None) => {}
        }
    }

    pub fn finish_frame(&mut self) {
        let Some(timestamp_us) = self.current_timestamp_us.take() else {
            return;
        };
        let mut contacts = core::mem::take(&mut self.current);
        let unchanged: Vec<TouchContact> = self
            .tracked
            .iter()
            .filter(|touch| contacts.iter().all(|contact| contact.contact_id != touch.contact_id))
            .map(|touch| {
                let contact = TouchContact::new(
                    touch.contact_id,
                    touch.x,
                    touch.y,
                    TouchContactFlags::UPDATE | TouchContactFlags::INRANGE | TouchContactFlags::INCONTACT,
                );
                match touch.pressure {
                    Some(pressure) => contact.with_pressure(pressure),
                    None => contact,
                }
            })
            .collect();
        contacts.extend(unchanged);

        let frame_offset = self
            .last_frame_timestamp_us
            .map_or(0, |last| timestamp_us.saturating_sub(last));
        self.last_frame_timestamp_us = (!self.tracked.is_empty()).then_some(timestamp_us);
        self.oldest_timestamp_us.get_or_insert(timestamp_us);
        self.frames.push(TouchFrame::new(frame_offset, contacts));
    }

    pub fn take_event(&mut self, now_us: u64) -> Option<TouchEventPdu> {
        self.finish_frame();
        if self.frames.is_empty() {
            return None;
        }
        let oldest_us = self.oldest_timestamp_us.take().unwrap_or(now_us);
        let encode_time = u32::try_from(now_us.saturating_sub(oldest_us) / 1000).unwrap_or(u32::MAX);
        Some(TouchEventPdu::new(encode_time, core::mem::take(&mut self.frames)))
    }

    pub fn cancel_all(&mut self, now_us: u64) {
        let touches: Vec<(u64, i32, i32)> = self
            .tracked
            .iter()
            .map(|touch| (touch.external_id, touch.x, touch.y))
            .collect();
        for (external_id, x, y) in touches {
            self.push(external_id, TouchPhase::Canceled, x, y, None, now_us);
        }
        self.finish_frame();
    }

    pub fn reset(&mut self) {
        self.tracked.clear();
        self.frames.clear();
        self.current.clear();
        self.current_timestamp_us = None;
        self.oldest_timestamp_us = None;
        self.last_frame_timestamp_us = None;
    }

    fn allocate_contact_id(&self) -> Option<u8> {
        if self.max_contacts <= self.tracked.len() {
            return None;
        }
        (0..=u8::MAX).find(|id| self.tracked.iter().all(|touch| touch.contact_id != *id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn two_finger_pinch_restates_unchanged_contacts_and_keeps_up_position() {
        let mut builder = TouchFrameBuilder::new(10);
        // Finger 1 down at (100, 100)
        builder.push(10, TouchPhase::Started, 100, 100, Some(512), 1_000);
        builder.finish_frame();
        // Finger 2 down at (200, 200) 10ms later while Finger 1 stays at (100, 100)
        builder.push(20, TouchPhase::Started, 200, 200, Some(512), 11_000);
        builder.finish_frame();
        // Both move 16ms later
        builder.push(10, TouchPhase::Moved, 90, 90, Some(512), 27_000);
        builder.push(20, TouchPhase::Moved, 210, 210, Some(512), 27_000);
        builder.finish_frame();
        // Finger 1 lifts (UP keeps last engaged position (90, 90))
        builder.push(10, TouchPhase::Ended, 85, 85, None, 43_000);
        builder.finish_frame();

        let pdu = builder.take_event(45_000).expect("should produce TouchEventPdu");
        assert_eq!(pdu.frames.len(), 4);

        // Frame 0: contact 0 DOWN at (100, 100)
        assert_eq!(pdu.frames[0].frame_offset, 0);
        assert_eq!(pdu.frames[0].contacts.len(), 1);
        assert_eq!(pdu.frames[0].contacts[0].contact_id, 0);
        assert_eq!(
            pdu.frames[0].contacts[0].contact_flags,
            TouchContactFlags::DOWN | TouchContactFlags::INRANGE | TouchContactFlags::INCONTACT
        );

        // Frame 1: contact 1 DOWN at (200, 200) + contact 0 restated as UPDATE at (100, 100)
        assert_eq!(pdu.frames[1].frame_offset, 10_000);
        assert_eq!(pdu.frames[1].contacts.len(), 2);

        // Frame 3: contact 0 UP keeps position (90, 90) + contact 1 restated as UPDATE at (210, 210)
        let c0 = pdu.frames[3]
            .contacts
            .iter()
            .find(|c| c.contact_id == 0)
            .unwrap();
        assert_eq!(c0.contact_flags, TouchContactFlags::UP);
        assert_eq!((c0.x, c0.y), (90, 90));
    }
}
