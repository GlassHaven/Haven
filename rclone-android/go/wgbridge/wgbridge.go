// Package wgbridge provides a minimal Go bridge over wireguard-go and its
// built-in gVisor netstack, exposed via gomobile for Haven's per-app
// WireGuard feature (#102).
//
// The shape of the public API is constrained by gomobile's binding rules:
//   - Exported types must be structs, not interfaces.
//   - Exported methods can only take/return: primitives, strings, []byte,
//     error, pointers to other exported struct types.
//   - Read-style methods return a fresh []byte instead of filling a caller
//     buffer because gomobile copies []byte parameters across the JNI
//     boundary and the caller's buffer wouldn't be updated.
//
// A tunnel is brought up with [StartTunnel], parsing a wg-quick style
// config (subset: [Interface]: PrivateKey + Address, [Peer]: PublicKey +
// Endpoint + AllowedIPs, optional PresharedKey + PersistentKeepalive).
// Callers then [TunnelHandle.Dial] to obtain a [Conn] whose Read/Write
// methods go through the userspace netstack, not the kernel socket layer.
package wgbridge

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/socks5"
)

// TunnelHandle is a live WireGuard tunnel backed by a userspace TUN + the
// gVisor netstack. Not safe for concurrent Close, but Dial is safe to call
// concurrently.
type TunnelHandle struct {
	dev      *device.Device
	tnet     *netstack.Net
	closed   bool
	socksLn  net.Listener
	mu       sync.Mutex
	// First IPv4 (preferred) or IPv6 address from the [Interface] block.
	// Used as the bind address for unconnected UDP sockets — gVisor's
	// netstack refuses to route from an unspecified source (0.0.0.0)
	// because it can't pick a primary IP from the NIC, so UDP writes
	// fail with ENETUNREACH unless we bind to a specific local IP.
	bindAddr netip.Addr
}

// Conn is a TCP connection through a [TunnelHandle]. Bound to gomobile;
// Read returns a fresh byte slice of up to size bytes because gomobile
// passes []byte arguments by copy.
type Conn struct {
	c net.Conn
}

// RemoteAddrHost returns the literal IP this connection was established
// to, or "" when unknown. Mirrors tsbridge.Conn.RemoteAddrHost (#539) so
// both tunnel backends expose the resolved peer identically.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// UDPConn is an unconnected UDP socket inside the tunnel's gVisor netstack.
// Send target is supplied per-WriteTo so the same conn can talk to many
// peers (Mosh's "client roams, server identifies by nonce" model). Bound
// to gomobile via [UDPRead] for the multi-value receive path.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result for gomobile, which only
// supports single-value-plus-error returns. Empty Data + non-nil error
// signals timeout (net.Error.Timeout()) or transport failure; callers
// translate the timeout case to "return nil" (Mosh) or equivalent.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// StartTunnel parses a wg-quick style config and brings a tunnel up. The
// returned handle must be closed via [TunnelHandle.Close]. Callers get a
// clear error message on parse / handshake failure.
func StartTunnel(configText string) (*TunnelHandle, error) {
	parsed, err := parseConfig(configText)
	if err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}

	tun, tnet, err := netstack.CreateNetTUN(parsed.addresses, parsed.dns, parsed.mtu)
	if err != nil {
		return nil, fmt.Errorf("create netstack TUN: %w", err)
	}

	// The netbirdio fork of wireguard-go exports conn.ControlFns so other
	// packages in the same binary can append socket control functions
	// (conn/export.go: "export controlFns for Android to use"), and
	// netbird's client/iface/bind init() does exactly that with its
	// Android ControlProtectSocket. That control demands a VpnService-style
	// protect function, which we never register — so with netbird linked
	// into libgojni, every UDP bind here fails with "listen udp4 :0:
	// socket protection function not set" (#637). Our sockets are pure
	// netstack and need no fd protection, so start each tunnel from a
	// clean control list. Package inits have all run by the time this is
	// called, and netbird's own binds apply their control via its wrapped
	// conn, not this list.
	*conn.ControlFns = nil

	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(
		device.LogLevelError,
		"haven-wg: ",
	))
	if err := dev.IpcSet(parsed.uapi); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wireguard IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wireguard Up: %w", err)
	}

	// Prefer the first IPv4 [Interface] Address for UDP binds — most
	// wg-quick configs put IPv4 first, and IPv4 is the common case for
	// peer-reachable destinations. Falls back to any address present.
	var bindAddr netip.Addr
	for _, a := range parsed.addresses {
		if a.Is4() {
			bindAddr = a
			break
		}
	}
	if !bindAddr.IsValid() && len(parsed.addresses) > 0 {
		bindAddr = parsed.addresses[0]
	}

	return &TunnelHandle{dev: dev, tnet: tnet, bindAddr: bindAddr}, nil
}

// Dial opens a TCP connection through the tunnel. timeoutMs <= 0 uses a
// generous default so callers don't have to special-case "no timeout".
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()

	c, err := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// ListenUDP opens an unconnected UDP socket inside the tunnel's netstack,
// bound to the tunnel's own [Interface] Address on an ephemeral port.
// The netstack itself is what guarantees the packets traverse the
// WireGuard tunnel rather than the kernel's default route — this is the
// same path TCP [TunnelHandle.Dial] uses, just for UDP.
//
// Three traps in the bind addr — empirically observed on a Pixel 8 Pro
// against a 10.0.0.0/24 wg-quick + 192.168.0.180 mosh-server:
//
//  1. netip.AddrPort{} (the zero value): netstack leaves its
//     NetworkProtocolNumber unset, then gVisor SIGABRTs on endpoint
//     creation. Don't.
//  2. netip.IPv4Unspecified() (0.0.0.0): netstack accepts the bind but
//     refuses to route on WriteTo because it can't resolve a primary IP
//     from the NIC for the unspecified source. WriteTo returns
//     "network is unreachable".
//  3. The tunnel's [Interface] Address (e.g. 10.0.0.2): works — gVisor
//     has a concrete source for routing.
//
// Returns an error if the tunnel is closed or has no usable interface
// address (shouldn't happen — [parseConfig] requires Address).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	bindAddr := t.bindAddr
	t.mu.Unlock()

	if !bindAddr.IsValid() {
		return nil, errors.New("tunnel has no usable Interface Address for UDP bind")
	}
	laddr := netip.AddrPortFrom(bindAddr, 0)
	pc, err := tnet.ListenUDPAddrPort(laddr)
	if err != nil {
		return nil, fmt.Errorf("netstack ListenUDP %s: %w", laddr, err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting
// this tunnel and returns its bound TCP port. Idempotent — repeat calls
// return the same port. Closing the tunnel tears the listener down.
//
// Used by transports that can't be intercepted at the Kotlin Socket
// layer (rclone via HTTPS_PROXY, IronRDP via a vendored SOCKS5 client)
// so a single SOCKS5 endpoint fronts every TCP transport.
func (t *TunnelHandle) StartSocksListener() (int, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return 0, errors.New("tunnel closed")
	}
	if t.socksLn != nil {
		port := t.socksLn.Addr().(*net.TCPAddr).Port
		t.mu.Unlock()
		return port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.mu.Unlock()
		return 0, fmt.Errorf("bind SOCKS5 listener: %w", err)
	}
	t.socksLn = ln
	tnet := t.tnet
	t.mu.Unlock()

	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})

	return ln.Addr().(*net.TCPAddr).Port, nil
}

// BindAddr returns the tunnel's WireGuard interface IP (the first
// [Interface] Address), as both the listen address for [ListenTCP] and
// the host a peer dials to reach a server bound on it. Empty if unset.
func (t *TunnelHandle) BindAddr() string {
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// Listener accepts inbound TCP connections on the tunnel's WireGuard
// interface address inside the gVisor netstack. Returned by
// [TunnelHandle.ListenTCP]. Closing the tunnel also tears down the
// underlying netstack, so a pending Accept then returns an error.
type Listener struct {
	ln net.Listener
}

// ListenTCP binds a TCP listener on the tunnel's WireGuard interface
// address (the same specific [Interface] Address the UDP path binds to —
// the netstack won't route from an unspecified 0.0.0.0). Lets an
// on-device server accept connections from WireGuard peers, e.g. the MCP
// endpoint reachable at <wg-interface-ip>:port and stable across roams.
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	addr := netip.AddrPortFrom(t.bindAddr, uint16(port))
	t.mu.Unlock()

	ln, err := tnet.ListenTCPAddrPort(addr)
	if err != nil {
		return nil, fmt.Errorf("netstack ListenTCP %s: %w", addr, err)
	}
	return &Listener{ln: ln}, nil
}

// Accept blocks until a peer connects, returning the connection wrapped
// as a [Conn]. A non-nil error means the listener (or tunnel) was closed.
func (l *Listener) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Addr returns the bound "ip:port" for diagnostics.
func (l *Listener) Addr() string {
	return l.ln.Addr().String()
}

// Close stops accepting. Idempotent.
func (l *Listener) Close() error {
	return l.ln.Close()
}

// Close tears down the tunnel. Idempotent.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	if t.socksLn != nil {
		t.socksLn.Close()
		t.socksLn = nil
	}
	if t.dev != nil {
		t.dev.Close()
		t.dev = nil
	}
}

// Read returns up to size bytes from the connection. A nil slice with a
// non-nil error signals EOF or a transport failure; callers translate to
// their platform's "end of stream" convention (e.g. -1 in Java).
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	return nil, err
}

// Write writes all of data. gomobile copies the slice across the JNI
// boundary, so we don't need to worry about the caller mutating the
// underlying array before we're done.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// ReadFrom blocks until a datagram arrives or the deadline expires. A
// timeoutMs <= 0 blocks indefinitely; >0 sets a one-shot read deadline.
// On timeout returns ("", 0, net.Error) with .Timeout() true — the Kotlin
// side checks for this and surfaces it as SocketTimeoutException for
// behavioural parity with java.net.DatagramSocket.
//
// size caps the receive buffer. Mosh uses 2048 (RECV_BUF_SIZE) which is
// well above the netstack's MTU (see defaultMTU); any pathological oversize
// datagram is truncated to size bytes.
func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		_ = u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	} else {
		_ = u.c.SetReadDeadline(time.Time{})
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		// gVisor's gonet.UDPConn always hands back *net.UDPAddr in
		// practice, but be defensive — at least surface the string form
		// so the caller can log something useful rather than panic on
		// the type assertion.
		return &UDPRead{Data: buf[:n], FromHost: addr.String(), FromPort: 0}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
}

// WriteTo sends data to host:port through the tunnel. host must be a
// literal IP (Mosh always knows the server IP via the SSH bootstrap's
// MOSH CONNECT line — no DNS resolution needed in the data path).
// Returns an error if host fails to parse or the underlying netstack
// write fails.
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	addr := &net.UDPAddr{IP: ip, Port: port}
	_, err := u.c.WriteTo(data, addr)
	return err
}

// Close closes the UDP socket. Idempotent.
func (u *UDPConn) Close() error {
	return u.c.Close()
}

// --- config parsing --------------------------------------------------------

// defaultMTU is the netstack tunnel MTU used when the [Interface] section
// doesn't set one. wireguard-go's own default is 1420 (1500 − 80 B WG/IPv6
// overhead), but the userspace gVisor netstack does no path-MTU discovery
// on the *outer* carrier path: on a link whose MTU is under 1500 (mobile /
// 4G, CGNAT, a nested tunnel) full-size inner segments become ~1500-byte WG
// datagrams that get silently dropped — small packets (keystrokes) flow, but
// sustained TCP (terminal output, transfers) stalls a few seconds in (#232).
// The kernel WG client avoids this via PMTU/MSS clamping; we don't, so we
// default to a conservative MTU instead. 1280 is the IPv6 minimum link MTU
// and the widely-used WG mobile default — it leaves headroom on constrained
// paths. Users on a clean 1500-MTU path can set `MTU = 1420` in [Interface]
// to reclaim throughput.
const defaultMTU = 1280

type parsedConfig struct {
	addresses []netip.Addr
	dns       []netip.Addr
	mtu       int    // [Interface] MTU, or defaultMTU when unset/invalid
	uapi      string // UAPI-format text for device.IpcSet
}

// parseConfig converts the wg-quick INI subset we support into the
// parsed form needed to bring up a netstack tunnel. We only look at the
// fields we actually use; unknown keys are ignored rather than rejected
// so users can paste a full wg-quick config verbatim.
func parseConfig(text string) (*parsedConfig, error) {
	lines := strings.Split(text, "\n")
	var (
		section       string
		interfaceSeen bool
		privateKeyB64 string
		addresses     []netip.Addr
		dns           []netip.Addr
		mtu           int
		peerBlocks    []map[string]string
		current       map[string]string
	)

	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			if section == "peer" {
				current = map[string]string{}
				peerBlocks = append(peerBlocks, current)
			} else if section == "interface" {
				interfaceSeen = true
				current = nil
			}
			continue
		}
		eq := strings.Index(line, "=")
		if eq < 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		val := strings.TrimSpace(line[eq+1:])
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				privateKeyB64 = val
			case "address":
				for _, a := range strings.Split(val, ",") {
					a = strings.TrimSpace(a)
					ip, err := parseAddrCIDR(a)
					if err != nil {
						return nil, fmt.Errorf("interface address %q: %w", a, err)
					}
					addresses = append(addresses, ip)
				}
			case "dns":
				// wg-quick allows hostname DNS entries (e.g. "fritz.box"
				// for a local Fritz!Box router). The userspace netstack
				// only understands IP addresses — it has no bootstrap
				// resolver to turn a hostname into an IP at tunnel-start
				// time, and the system resolver would either defeat the
				// tunnel (DNS leak) or fail for remote users. Skip
				// unparseable entries rather than reject the whole
				// config; users who need in-tunnel name resolution can
				// add /etc/hosts or use IP addresses directly.
				for _, a := range strings.Split(val, ",") {
					a = strings.TrimSpace(a)
					if a == "" {
						continue
					}
					ip, err := netip.ParseAddr(a)
					if err != nil {
						// Silently drop; useful DNS entries still work.
						continue
					}
					dns = append(dns, ip)
				}
			case "mtu":
				// Respect an explicit [Interface] MTU; clamp to a sane
				// range and otherwise fall through to defaultMTU.
				if m, err := strconv.Atoi(val); err == nil && m >= 576 && m <= 1500 {
					mtu = m
				}
			}
		case "peer":
			if current != nil {
				current[key] = val
			}
		}
	}

	if !interfaceSeen {
		return nil, errors.New("missing [Interface] section")
	}
	if privateKeyB64 == "" {
		return nil, errors.New("missing Interface.PrivateKey")
	}
	if len(addresses) == 0 {
		return nil, errors.New("missing Interface.Address")
	}
	if len(peerBlocks) == 0 {
		return nil, errors.New("missing [Peer] section")
	}

	privateKeyHex, err := base64ToHex(privateKeyB64)
	if err != nil {
		return nil, fmt.Errorf("decode Interface.PrivateKey: %w", err)
	}

	var uapi strings.Builder
	uapi.WriteString("private_key=" + privateKeyHex + "\n")
	for i, peer := range peerBlocks {
		pubB64 := peer["publickey"]
		if pubB64 == "" {
			return nil, fmt.Errorf("peer %d: missing PublicKey", i)
		}
		endpoint := peer["endpoint"]
		if endpoint == "" {
			return nil, fmt.Errorf("peer %d: missing Endpoint", i)
		}
		resolvedEndpoint, err := resolveEndpoint(endpoint)
		if err != nil {
			return nil, fmt.Errorf("peer %d Endpoint %q: %w", i, endpoint, err)
		}
		pubHex, err := base64ToHex(pubB64)
		if err != nil {
			return nil, fmt.Errorf("peer %d PublicKey: %w", i, err)
		}
		uapi.WriteString("public_key=" + pubHex + "\n")
		uapi.WriteString("endpoint=" + resolvedEndpoint + "\n")
		if psk := peer["presharedkey"]; psk != "" {
			pskHex, err := base64ToHex(psk)
			if err != nil {
				return nil, fmt.Errorf("peer %d PresharedKey: %w", i, err)
			}
			uapi.WriteString("preshared_key=" + pskHex + "\n")
		}
		if ka := peer["persistentkeepalive"]; ka != "" {
			if _, err := strconv.Atoi(ka); err != nil {
				return nil, fmt.Errorf("peer %d PersistentKeepalive: %w", i, err)
			}
			uapi.WriteString("persistent_keepalive_interval=" + ka + "\n")
		}
		allowed := peer["allowedips"]
		if allowed == "" {
			// Sane default — route everything through the tunnel. Most
			// wg-quick configs set this explicitly anyway.
			allowed = "0.0.0.0/0, ::/0"
		}
		for _, a := range strings.Split(allowed, ",") {
			a = strings.TrimSpace(a)
			if a == "" {
				continue
			}
			uapi.WriteString("allowed_ip=" + a + "\n")
		}
	}

	if mtu == 0 {
		mtu = defaultMTU
	}
	return &parsedConfig{
		addresses: addresses,
		dns:       dns,
		mtu:       mtu,
		uapi:      uapi.String(),
	}, nil
}

// parseAddrCIDR accepts "10.0.0.2" or "10.0.0.2/32" and returns the address
// portion. The netstack only cares about the host IP; the /mask is informational.
func parseAddrCIDR(s string) (netip.Addr, error) {
	if slash := strings.Index(s, "/"); slash >= 0 {
		prefix, err := netip.ParsePrefix(s)
		if err != nil {
			return netip.Addr{}, err
		}
		return prefix.Addr(), nil
	}
	return netip.ParseAddr(s)
}

func base64ToHex(b64 string) (string, error) {
	bytes, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

// endpointResolver is indirection for testing. In production it's
// [net.DefaultResolver.LookupHost]; tests replace it to avoid hitting
// the network.
var endpointResolver = func(host string) ([]string, error) {
	return net.LookupHost(host)
}

// resolveEndpoint turns a wg-quick style "host:port" Endpoint into the
// "ip:port" shape wireguard-go's UAPI demands. Real wg-quick does the
// same via the system resolver before calling `wg`. For dynamic-DNS
// WireGuard servers (e.g. myfritz.net) this resolution is unavoidable —
// we have to find the peer's current IP before we can hand shake.
//
// Prefers IPv4 over IPv6 because many home networks have flaky IPv6
// reachability to the WireGuard peer even when AAAA records exist.
func resolveEndpoint(endpoint string) (string, error) {
	host, port, err := net.SplitHostPort(endpoint)
	if err != nil {
		return "", fmt.Errorf("invalid host:port: %w", err)
	}
	// Already an IP — no resolution needed.
	if addr, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(addr, parsePort(port)).String(), nil
	}
	addrs, err := endpointResolver(host)
	if err != nil {
		return "", fmt.Errorf("resolve %q: %w", host, err)
	}
	if len(addrs) == 0 {
		return "", fmt.Errorf("resolve %q: no addresses", host)
	}
	// Prefer the first IPv4 if present; otherwise take the first overall.
	var chosen netip.Addr
	for _, a := range addrs {
		parsed, err := netip.ParseAddr(a)
		if err != nil {
			continue
		}
		if parsed.Is4() {
			chosen = parsed
			break
		}
		if !chosen.IsValid() {
			chosen = parsed
		}
	}
	if !chosen.IsValid() {
		return "", fmt.Errorf("resolve %q: no parseable addresses in %v", host, addrs)
	}
	return netip.AddrPortFrom(chosen, parsePort(port)).String(), nil
}

func parsePort(p string) uint16 {
	v, err := strconv.ParseUint(p, 10, 16)
	if err != nil {
		return 0
	}
	return uint16(v)
}
