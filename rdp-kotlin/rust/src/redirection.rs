//! RDP Server Redirection Packet parser — MS-RDPBCGR §2.2.13.1.1
//! (`RDP_SERVER_REDIRECTION_PACKET`).
//!
//! GNOME Remote Desktop (and Windows RDS load-balancers) send a server
//! redirection PDU to hand a connecting client off to another endpoint /
//! session. Haven follows it by reconnecting, injecting the
//! `LoadBalanceInfo` routing token as the X.224 negotiation cookie
//! (`Cookie: msts=…`) and using the redirected target/credentials when the
//! server supplies them. Without this, GRD's redirect is ignored, the
//! original transport times out, and the user sees a black screen (#117).
//!
//! Field layout, wire order, and little-endian encoding cross-referenced
//! against FreeRDP `libfreerdp/core/redirection.c`. Flag values are the
//! MS-RDPBCGR §2.2.13.1.1 `LB_*` constants.
//!
//! Scope: this module (a) parses the redirection *packet body* (from the
//! `Flags` UINT16 onward — [`parse_redirection_packet`]) and (b) locates that
//! body inside a live session frame, recognising the Enhanced Security Server
//! Redirection PDU from raw X.224/MCS bytes ([`detect_server_redirect`]). Both
//! are unit-tested, including a round-trip through IronRDP's real MCS decoder.
//! The session loop uses (b) to report a redirect precisely instead of dying
//! to a black screen.
//!
//! NOT yet done: *following* the redirect (reconnecting with the routing token
//! replayed as the X.224 nego cookie). That step's wire details — how GRD
//! frames `LoadBalanceInfo`, and whether a pad precedes the body — can't be
//! pinned down without a real GRD redirect capture, so it's deferred rather
//! than shipped on guessed framing (see #117).

// RedirFlags (LB_*) — MS-RDPBCGR §2.2.13.1.1, little-endian UINT32 bitmask.
const LB_TARGET_NET_ADDRESS: u32 = 0x0000_0001;
const LB_LOAD_BALANCE_INFO: u32 = 0x0000_0002;
const LB_USERNAME: u32 = 0x0000_0004;
const LB_DOMAIN: u32 = 0x0000_0008;
const LB_PASSWORD: u32 = 0x0000_0010;
#[allow(dead_code)]
const LB_DONTSTOREUSERNAME: u32 = 0x0000_0020;
#[allow(dead_code)]
const LB_SMARTCARD_LOGON: u32 = 0x0000_0040;
const LB_NOREDIRECT: u32 = 0x0000_0080;
const LB_TARGET_FQDN: u32 = 0x0000_0100;
const LB_TARGET_NETBIOS_NAME: u32 = 0x0000_0200;
const LB_TARGET_NET_ADDRESSES: u32 = 0x0000_0800;
const LB_CLIENT_TSV_URL: u32 = 0x0000_1000;
#[allow(dead_code)]
const LB_SERVER_TSV_CAPABLE: u32 = 0x0000_2000;
#[allow(dead_code)]
const LB_PASSWORD_IS_PK_ENCRYPTED: u32 = 0x0000_4000;
const LB_REDIRECTION_GUID: u32 = 0x0000_8000;
const LB_TARGET_CERTIFICATE: u32 = 0x0001_0000;

/// Bits we know how to skip/consume. A redirection packet whose RedirFlags
/// contains bits outside this mask is treated as unparseable (we'd lose
/// cursor alignment on the unknown field).
const KNOWN_FLAGS: u32 = LB_TARGET_NET_ADDRESS
    | LB_LOAD_BALANCE_INFO
    | LB_USERNAME
    | LB_DOMAIN
    | LB_PASSWORD
    | LB_DONTSTOREUSERNAME
    | LB_SMARTCARD_LOGON
    | LB_NOREDIRECT
    | LB_TARGET_FQDN
    | LB_TARGET_NETBIOS_NAME
    | LB_TARGET_NET_ADDRESSES
    | LB_CLIENT_TSV_URL
    | LB_SERVER_TSV_CAPABLE
    | LB_PASSWORD_IS_PK_ENCRYPTED
    | LB_REDIRECTION_GUID
    | LB_TARGET_CERTIFICATE;

/// Parsed server-redirection instructions. Only the fields Haven needs to
/// follow a redirect are surfaced; other present fields are consumed (to
/// keep the cursor aligned) but discarded.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RedirectionInfo {
    pub session_id: u32,
    pub redir_flags: u32,
    /// Explicit target (IP/host) to reconnect to, if the server supplied one.
    pub target_net_address: Option<String>,
    pub target_fqdn: Option<String>,
    /// Raw routing token — replayed as the X.224 nego cookie on reconnect.
    pub load_balance_info: Option<Vec<u8>>,
    pub username: Option<String>,
    pub domain: Option<String>,
    pub password: Option<Vec<u8>>,
    /// LB_NOREDIRECT: server says "do not redirect" (use the current connection).
    pub no_redirect: bool,
}

impl From<&ironrdp_pdu::rdp::server_redirection::ServerRedirectionPdu> for RedirectionInfo {
    fn from(pdu: &ironrdp_pdu::rdp::server_redirection::ServerRedirectionPdu) -> Self {
        Self {
            session_id: pdu.session_id,
            redir_flags: pdu.flags.bits(),
            target_net_address: pdu.target_net_address.clone(),
            target_fqdn: pdu.target_fqdn.clone(),
            load_balance_info: pdu.load_balance_info.clone(),
            username: pdu.username.clone(),
            domain: pdu.domain.clone(),
            password: pdu.password.clone(),
            no_redirect: pdu.is_informational(),
        }
    }
}

impl RedirectionInfo {
    /// The host to reconnect to: the explicit target net address, else the
    /// target FQDN, else `None` meaning "reconnect to the same endpoint"
    /// (GRD's same-host session handover).
    pub fn target_host(&self) -> Option<String> {
        self.target_net_address
            .clone()
            .filter(|s| !s.is_empty())
            .or_else(|| self.target_fqdn.clone().filter(|s| !s.is_empty()))
    }

    /// Everything the reconnect needs to follow this redirection, or `None`
    /// when it must not / cannot be followed.
    ///
    /// Wire facts from a real GNOME Remote Desktop handover (#117, reporter
    /// capture): `LoadBalanceInfo` is the complete X.224 routing-token line
    /// `Cookie: msts=<value>\r\n`, the username is a server-generated one-time
    /// credential, and the password is UTF-16LE with a trailing NUL.
    /// IronRDP's `NegoRequestData::routing_token` re-adds the `Cookie: msts=`
    /// prefix and CRLF itself, so only the bare value is extracted here — a
    /// token in any other shape is not expressible through that API and
    /// returns `None` rather than sending a mangled line.
    pub fn follow_plan(&self) -> Option<RedirectFollow> {
        if self.no_redirect {
            // LB_NOREDIRECT: informational packet, the server forbids following.
            return None;
        }
        let lb = self.load_balance_info.as_deref()?;
        let value = lb
            .strip_prefix(b"Cookie: msts=")
            .and_then(|rest| rest.strip_suffix(b"\r\n"))
            .and_then(|v| core::str::from_utf8(v).ok())
            .filter(|v| !v.is_empty())?;
        Some(RedirectFollow {
            routing_token: value.to_owned(),
            username: self.username.clone().filter(|s| !s.is_empty()),
            domain: self.domain.clone().filter(|s| !s.is_empty()),
            password: self.password.as_deref().and_then(decode_utf16le_password),
        })
    }
}

/// The replay recipe for one server redirection (MS-RDPBCGR §2.2.13.1):
/// reconnect to the same endpoint (or [`RedirectionInfo::target_host`]),
/// present [`routing_token`](Self::routing_token) in the X.224 Connection
/// Request, and authenticate with the one-time credentials when supplied.
#[derive(Debug, Clone)]
pub struct RedirectFollow {
    pub routing_token: String,
    pub username: Option<String>,
    pub domain: Option<String>,
    pub password: Option<String>,
}

/// UTF-16LE → String for the redirection PDU's password field, dropping
/// trailing NUL terminators. `None` on odd length or invalid UTF-16 —
/// authenticating with a corrupted password can only fail slower.
fn decode_utf16le_password(bytes: &[u8]) -> Option<String> {
    if bytes.len() % 2 != 0 {
        return None;
    }
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|c| u16::from_le_bytes([c[0], c[1]]))
        .collect();
    let end = units.iter().rposition(|&u| u != 0).map_or(0, |i| i + 1);
    String::from_utf16(&units[..end]).ok()
}

/// Minimal little-endian cursor that returns `None` on a short read rather
/// than panicking — untrusted server bytes must never crash the reader.
struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }
    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }
    fn read_u16(&mut self) -> Option<u16> {
        let s = self.take(2)?;
        Some(u16::from_le_bytes([s[0], s[1]]))
    }
    fn read_u32(&mut self) -> Option<u32> {
        let s = self.take(4)?;
        Some(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.remaining() < n {
            return None;
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Some(s)
    }
    /// A UINT32-length-prefixed blob (the encoding of every optional field).
    fn read_len_blob(&mut self) -> Option<&'a [u8]> {
        let len = self.read_u32()? as usize;
        self.take(len)
    }
}

/// Decode a UTF-16LE string field, stripping any trailing NUL terminator.
fn utf16le(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|p| u16::from_le_bytes([p[0], p[1]]))
        .collect();
    String::from_utf16_lossy(&units)
        .trim_end_matches('\0')
        .to_string()
}

/// Parse an `RDP_SERVER_REDIRECTION_PACKET` body, starting at the leading
/// `Flags` UINT16. Returns `None` if the bytes are too short, carry unknown
/// RedirFlags bits, or a flagged field is truncated.
pub fn parse_redirection_packet(packet: &[u8]) -> Option<RedirectionInfo> {
    let mut c = Cursor::new(packet);
    let _flags = c.read_u16()?; // SEC_REDIRECTION_PKT (0x0400) for std-sec; not validated.
    let _length = c.read_u16()?; // overall length; we bound on the buffer instead.
    let session_id = c.read_u32()?;
    let redir = c.read_u32()?;

    // Refuse packets with flags we don't know how to consume — parsing past
    // an unknown variable-length field would desync the cursor and produce
    // garbage. Better to decline than to mis-parse.
    if redir & !KNOWN_FLAGS != 0 {
        return None;
    }

    let mut info = RedirectionInfo {
        session_id,
        redir_flags: redir,
        no_redirect: redir & LB_NOREDIRECT != 0,
        ..Default::default()
    };

    // Optional fields in wire order (FreeRDP redirection.c). Each present
    // field is a UINT32 length followed by that many bytes; a truncated
    // field aborts the parse (`?`).
    if redir & LB_TARGET_NET_ADDRESS != 0 {
        info.target_net_address = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_LOAD_BALANCE_INFO != 0 {
        info.load_balance_info = Some(c.read_len_blob()?.to_vec());
    }
    if redir & LB_USERNAME != 0 {
        info.username = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_DOMAIN != 0 {
        info.domain = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_PASSWORD != 0 {
        info.password = Some(c.read_len_blob()?.to_vec());
    }
    if redir & LB_TARGET_FQDN != 0 {
        info.target_fqdn = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_TARGET_NETBIOS_NAME != 0 {
        c.read_len_blob()?; // not needed for reconnect
    }
    if redir & LB_CLIENT_TSV_URL != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_REDIRECTION_GUID != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_TARGET_CERTIFICATE != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_TARGET_NET_ADDRESSES != 0 {
        // UINT32 length covering [addressCount + Unicode address strings].
        c.read_len_blob()?;
    }

    Some(info)
}

// --- Locating the redirection packet inside a live Share Control frame ---
//
// Over enhanced (TLS/CredSSP) security, GRD sends the redirection as an
// *Enhanced Security Server Redirection PDU* (MS-RDPBCGR §2.2.13.3): an MCS
// SendDataIndication whose Share Control Header carries
// `pduType == PDUTYPE_SERVER_REDIR_PKT`, followed by the
// `RDP_SERVER_REDIRECTION_PACKET` body. IronRDP has no redirect support, so
// `ActiveStage::process` rejects it as "unexpected share control PDU type"
// and the session dies to a black screen (#117). We recognise it from the raw
// frame so the failure can be reported precisely.

/// Share Control Header `pduType` low-nibble for a server-redirection PDU.
const PDUTYPE_SERVER_REDIR_PKT: u16 = 0xa;
/// Mask isolating the pduType from the version bits (matches IronRDP's
/// `SHARE_CONTROL_HEADER_MASK`).
const SHARE_CONTROL_TYPE_MASK: u16 = 0xf;
/// `Flags` value that opens every `RDP_SERVER_REDIRECTION_PACKET` body.
const SEC_REDIRECTION_PKT: u16 = 0x0400;

/// Given the Share Control payload (an MCS SendDataIndication's user data,
/// i.e. starting at the Share Control Header's `totalLength`), return
/// `Some(info)` iff it is a server-redirection PDU.
///
/// The `pduType` nibble is the unambiguous, decisive signal ("this is a
/// redirect"). The redirection body then sits either immediately after the
/// 6-byte Share Control Header or after a 2-octet pad — layouts differ across
/// servers and we can't yet device-verify which GRD emits (#117), so we locate
/// the body by its `SEC_REDIRECTION_PKT` marker at either offset. If the type
/// matched but the body doesn't parse, a bare `RedirectionInfo` is still
/// returned so callers can report *that* a redirect happened even when the
/// optional fields are unreadable.
pub fn parse_share_control_redirect(user_data: &[u8]) -> Option<RedirectionInfo> {
    // Share Control Header: totalLength(2) + pduType(2) + pduSource(2).
    if user_data.len() < 6 {
        return None;
    }
    let pdu_type = u16::from_le_bytes([user_data[2], user_data[3]]);
    if pdu_type & SHARE_CONTROL_TYPE_MASK != PDUTYPE_SERVER_REDIR_PKT {
        return None;
    }

    for start in [6usize, 8] {
        if start + 2 <= user_data.len()
            && u16::from_le_bytes([user_data[start], user_data[start + 1]]) == SEC_REDIRECTION_PKT
        {
            if let Some(info) = parse_redirection_packet(&user_data[start..]) {
                return Some(info);
            }
        }
    }

    // Definitely a redirect (type nibble matched) but the body was where we
    // didn't expect or didn't parse — surface a bare marker regardless.
    Some(RedirectionInfo::default())
}

/// Recognise a server-redirection PDU straight from a raw session frame
/// (post-TLS X.224/MCS bytes as read by the session loop). Peels the MCS
/// SendDataIndication with IronRDP's decoder — the same one the working
/// slow-path-bitmap handler uses — then defers to
/// [`parse_share_control_redirect`]. Returns `None` for any non-redirect frame.
pub fn detect_server_redirect(frame: &[u8]) -> Option<RedirectionInfo> {
    let ctx = ironrdp_pdu::mcs::decode_send_data_indication(frame).ok()?;
    parse_share_control_redirect(ctx.user_data)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Wrap an already-encoded redirection body in a Share Control Header with
    /// the given pduType nibble (`0xa` == server redirection).
    fn share_control(pdu_type_nibble: u16, body: &[u8]) -> Vec<u8> {
        const PROTOCOL_VERSION: u16 = 0x10; // IronRDP's TS_PROTOCOL_VERSION
        let mut v = Vec::new();
        let total = (6 + body.len()) as u16;
        v.extend_from_slice(&total.to_le_bytes()); // totalLength
        v.extend_from_slice(&(PROTOCOL_VERSION | pdu_type_nibble).to_le_bytes()); // pduType|version
        v.extend_from_slice(&0x03eau16.to_le_bytes()); // pduSource (server channel)
        v.extend_from_slice(body);
        v
    }

    /// Build a redirection packet body: Flags, Length(placeholder),
    /// SessionID, RedirFlags, then the already-encoded optional fields.
    fn pkt(session_id: u32, redir: u32, fields: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x0400u16.to_le_bytes()); // Flags = SEC_REDIRECTION_PKT
        v.extend_from_slice(&0u16.to_le_bytes()); // Length placeholder
        v.extend_from_slice(&session_id.to_le_bytes());
        v.extend_from_slice(&redir.to_le_bytes());
        v.extend_from_slice(fields);
        // backfill total length
        let len = v.len() as u16;
        v[2..4].copy_from_slice(&len.to_le_bytes());
        v
    }

    fn len_blob(bytes: &[u8]) -> Vec<u8> {
        let mut v = (bytes.len() as u32).to_le_bytes().to_vec();
        v.extend_from_slice(bytes);
        v
    }

    fn utf16le_bytes(s: &str) -> Vec<u8> {
        let mut v: Vec<u8> = s.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        v.extend_from_slice(&0u16.to_le_bytes()); // NUL terminator
        v
    }

    #[test]
    fn empty_flags_parses_to_bare_info() {
        let p = pkt(7, 0, &[]);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.session_id, 7);
        assert_eq!(info.redir_flags, 0);
        assert!(info.target_host().is_none());
        assert!(info.load_balance_info.is_none());
        assert!(!info.no_redirect);
    }

    #[test]
    fn load_balance_info_only_is_same_host_handover() {
        // GRD's typical case: a routing token, no explicit target (reconnect
        // to the same endpoint carrying the cookie).
        let token = b"Cookie: msts=12345.67890.0000\r\n";
        let p = pkt(0x55, LB_LOAD_BALANCE_INFO, &len_blob(token));
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.load_balance_info.as_deref(), Some(&token[..]));
        assert!(info.target_host().is_none(), "no target => same-host handover");
    }

    #[test]
    fn target_address_and_credentials_in_wire_order() {
        let mut fields = Vec::new();
        fields.extend_from_slice(&len_blob(&utf16le_bytes("192.168.0.42"))); // TARGET_NET_ADDRESS
        fields.extend_from_slice(&len_blob(b"\x01\x02\x03\x04")); // LOAD_BALANCE_INFO
        fields.extend_from_slice(&len_blob(&utf16le_bytes("ian"))); // USERNAME
        fields.extend_from_slice(&len_blob(&utf16le_bytes("WORKGROUP"))); // DOMAIN
        fields.extend_from_slice(&len_blob(&utf16le_bytes("FQDN.example"))); // TARGET_FQDN
        let flags = LB_TARGET_NET_ADDRESS | LB_LOAD_BALANCE_INFO | LB_USERNAME | LB_DOMAIN | LB_TARGET_FQDN;
        let p = pkt(1, flags, &fields);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.target_net_address.as_deref(), Some("192.168.0.42"));
        assert_eq!(info.target_host().as_deref(), Some("192.168.0.42")); // net addr wins over FQDN
        assert_eq!(info.load_balance_info.as_deref(), Some(&b"\x01\x02\x03\x04"[..]));
        assert_eq!(info.username.as_deref(), Some("ian"));
        assert_eq!(info.domain.as_deref(), Some("WORKGROUP"));
        assert_eq!(info.target_fqdn.as_deref(), Some("FQDN.example"));
    }

    #[test]
    fn fqdn_used_as_target_when_no_net_address() {
        let p = pkt(1, LB_TARGET_FQDN, &len_blob(&utf16le_bytes("host.lan")));
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.target_host().as_deref(), Some("host.lan"));
    }

    #[test]
    fn no_redirect_flag_is_surfaced() {
        let p = pkt(1, LB_NOREDIRECT, &[]);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert!(info.no_redirect);
    }

    #[test]
    fn unknown_flag_bit_is_declined() {
        // A bit outside KNOWN_FLAGS would desync the cursor — decline.
        let p = pkt(1, 0x8000_0000, &[]);
        assert!(parse_redirection_packet(&p).is_none());
    }

    #[test]
    fn truncated_field_returns_none_not_panic() {
        // Flag says LOAD_BALANCE_INFO present but the blob is cut short.
        let mut p = pkt(1, LB_LOAD_BALANCE_INFO, &[]);
        p.extend_from_slice(&100u32.to_le_bytes()); // claims 100 bytes, none follow
        assert!(parse_redirection_packet(&p).is_none());
    }

    #[test]
    fn short_header_returns_none() {
        assert!(parse_redirection_packet(&[0x00, 0x04]).is_none());
        assert!(parse_redirection_packet(&[]).is_none());
    }

    #[test]
    fn skipped_fields_keep_cursor_aligned() {
        // NETBIOS + TSV_URL + GUID + CERT + NET_ADDRESSES are consumed but
        // discarded; a trailing field we *do* want must still parse correctly.
        let mut fields = Vec::new();
        fields.extend_from_slice(&len_blob(b"\xaa\xbb")); // LOAD_BALANCE_INFO (wanted)
        fields.extend_from_slice(&len_blob(&utf16le_bytes("nb"))); // NETBIOS (skipped)
        fields.extend_from_slice(&len_blob(b"tsv-url-bytes")); // CLIENT_TSV_URL (skipped)
        fields.extend_from_slice(&len_blob(b"\x00\x11\x22\x33\x44\x55\x66\x77\x88\x99\xaa\xbb\xcc\xdd\xee\xff")); // GUID (skipped, 16B)
        let flags = LB_LOAD_BALANCE_INFO | LB_TARGET_NETBIOS_NAME | LB_CLIENT_TSV_URL | LB_REDIRECTION_GUID;
        let p = pkt(1, flags, &fields);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.load_balance_info.as_deref(), Some(&b"\xaa\xbb"[..]));
    }

    // --- parse_share_control_redirect ---

    #[test]
    fn share_control_redirect_body_immediately_after_header() {
        // GRD same-host handover: routing token, no explicit target.
        let token = b"Cookie: msts=12345.67890.0000\r\n";
        let body = pkt(0x55, LB_LOAD_BALANCE_INFO, &len_blob(token));
        let ud = share_control(PDUTYPE_SERVER_REDIR_PKT, &body);
        let info = parse_share_control_redirect(&ud).expect("recognised as redirect");
        assert_eq!(info.load_balance_info.as_deref(), Some(&token[..]));
        assert!(info.target_host().is_none());
    }

    #[test]
    fn share_control_redirect_body_after_two_octet_pad() {
        // Some layouts insert a 2-octet pad before the redirection packet;
        // the SEC_REDIRECTION_PKT marker scan must find it at offset 8.
        let body = pkt(1, LB_TARGET_NET_ADDRESS, &len_blob(&utf16le_bytes("10.0.0.7")));
        let mut padded = vec![0u8, 0u8];
        padded.extend_from_slice(&body);
        let ud = share_control(PDUTYPE_SERVER_REDIR_PKT, &padded);
        let info = parse_share_control_redirect(&ud).expect("recognised as redirect");
        assert_eq!(info.target_host().as_deref(), Some("10.0.0.7"));
    }

    #[test]
    fn non_redirect_pdu_type_is_ignored() {
        // A Data PDU (nibble 0x7) must not be mistaken for a redirect.
        let ud = share_control(0x7, &[0u8; 16]);
        assert!(parse_share_control_redirect(&ud).is_none());
    }

    #[test]
    fn redirect_type_with_unparseable_body_still_flags_redirect() {
        // pduType says redirect but there's no SEC_REDIRECTION_PKT marker:
        // we still report *a* redirect (bare marker) so the caller can say so.
        let ud = share_control(PDUTYPE_SERVER_REDIR_PKT, &[0xffu8; 12]);
        let info = parse_share_control_redirect(&ud).expect("still a redirect");
        assert_eq!(info, RedirectionInfo::default());
        assert!(info.target_host().is_none());
    }

    #[test]
    fn share_control_too_short_is_none() {
        assert!(parse_share_control_redirect(&[0x01, 0x02, 0x1a]).is_none());
        assert!(parse_share_control_redirect(&[]).is_none());
    }

    // --- detect_server_redirect: end-to-end through the real MCS decoder ---

    /// Encode a genuine X.224/MCS SendDataIndication carrying `user_data`, so
    /// the test exercises the same `decode_send_data_indication` path the live
    /// session loop uses — not just the inner Share Control parse.
    fn send_data_indication(user_data: &[u8]) -> Vec<u8> {
        use ironrdp_core::encode_vec;
        use ironrdp_pdu::mcs::SendDataIndication;
        use ironrdp_pdu::x224::X224;
        use std::borrow::Cow;
        let sdi = SendDataIndication {
            initiator_id: 1002,
            channel_id: 1003,
            user_data: Cow::Borrowed(user_data),
        };
        encode_vec(&X224(sdi)).expect("encode SendDataIndication")
    }

    #[test]
    fn detect_from_real_mcs_frame_with_routing_token() {
        let token = b"Cookie: msts=7.8.0\r\n";
        let body = pkt(0x99, LB_LOAD_BALANCE_INFO, &len_blob(token));
        let ud = share_control(PDUTYPE_SERVER_REDIR_PKT, &body);
        let frame = send_data_indication(&ud);
        let info = detect_server_redirect(&frame).expect("redirect detected from MCS frame");
        assert_eq!(info.load_balance_info.as_deref(), Some(&token[..]));
    }

    #[test]
    fn detect_ignores_non_redirect_mcs_frame() {
        // A Data-PDU Share Control payload wrapped in a real MCS frame must
        // not be flagged as a redirect.
        let ud = share_control(0x7, &[0u8; 8]);
        let frame = send_data_indication(&ud);
        assert!(detect_server_redirect(&frame).is_none());
    }

    #[test]
    fn detect_ignores_garbage_frame() {
        // Random bytes aren't a valid MCS SendDataIndication → None, no panic.
        assert!(detect_server_redirect(&[0xde, 0xad, 0xbe, 0xef]).is_none());
        assert!(detect_server_redirect(&[]).is_none());
    }

    fn grd_style_info() -> RedirectionInfo {
        // The field shape from the real GRD capture in #117 (values changed).
        RedirectionInfo {
            session_id: 0,
            redir_flags: 0x18016,
            target_net_address: None,
            target_fqdn: None,
            load_balance_info: Some(b"Cookie: msts=2282941292\r\n".to_vec()),
            username: Some("one-time-user".to_owned()),
            domain: None,
            // "pw" in UTF-16LE with a trailing NUL, as the wire carries it.
            password: Some(vec![b'p', 0, b'w', 0, 0, 0]),
            no_redirect: false,
        }
    }

    #[test]
    fn follow_plan_extracts_grd_token_and_credentials() {
        let plan = grd_style_info().follow_plan().expect("followable");
        assert_eq!(plan.routing_token, "2282941292");
        assert_eq!(plan.username.as_deref(), Some("one-time-user"));
        assert_eq!(plan.password.as_deref(), Some("pw"));
        assert!(plan.domain.is_none());
    }

    #[test]
    fn follow_plan_respects_noredirect() {
        let mut info = grd_style_info();
        info.no_redirect = true;
        assert!(info.follow_plan().is_none());
    }

    #[test]
    fn follow_plan_rejects_unexpressible_tokens() {
        // A load-balancer token that is not the msts cookie line cannot be
        // replayed through NegoRequestData::routing_token — must decline, not
        // mangle.
        let mut info = grd_style_info();
        info.load_balance_info = Some(b"opaque-binary-token".to_vec());
        assert!(info.follow_plan().is_none());

        info.load_balance_info = Some(b"Cookie: msts=\r\n".to_vec());
        assert!(info.follow_plan().is_none(), "empty token value");

        info.load_balance_info = None;
        assert!(info.follow_plan().is_none(), "no token at all");
    }

    #[test]
    fn utf16le_password_decode_handles_terminators_and_garbage() {
        assert_eq!(
            decode_utf16le_password(&[b'a', 0, b'b', 0, 0, 0, 0, 0]).as_deref(),
            Some("ab"),
        );
        assert_eq!(decode_utf16le_password(&[]).as_deref(), Some(""));
        // Odd length: not UTF-16, refuse rather than guess.
        assert!(decode_utf16le_password(&[b'a', 0, b'b']).is_none());
        // Unpaired surrogate: invalid UTF-16, refuse.
        assert!(decode_utf16le_password(&[0x00, 0xD8]).is_none());
    }

    #[test]
    fn from_ironrdp_server_redirection_pdu_preserves_follow_plan() {
        use ironrdp_pdu::rdp::server_redirection::{ServerRedirectionFlags, ServerRedirectionPdu};

        let pdu = ServerRedirectionPdu {
            session_id: 42,
            flags: ServerRedirectionFlags::LOAD_BALANCE_INFO
                | ServerRedirectionFlags::USERNAME
                | ServerRedirectionFlags::PASSWORD,
            load_balance_info: Some(b"Cookie: msts=987654\r\n".to_vec()),
            username: Some("grd-user".to_owned()),
            password: Some(vec![b's', 0, b'3', 0, b'c', 0, 0, 0]),
            ..Default::default()
        };

        let info = RedirectionInfo::from(&pdu);
        assert_eq!(info.session_id, 42);
        assert!(!info.no_redirect);
        let plan = info.follow_plan().expect("follow plan extracted");
        assert_eq!(plan.routing_token, "987654");
        assert_eq!(plan.username.as_deref(), Some("grd-user"));
        assert_eq!(plan.password.as_deref(), Some("s3c"));
    }
}
