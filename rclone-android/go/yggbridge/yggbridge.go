// Package yggbridge provides a minimal Go bridge over the yggdrasil-go mesh
// router and its ipv6rwc packet pipe, fronted by a gVisor netstack and exposed
// via gomobile for Haven's Yggdrasil tunnel type.
//
// Yggdrasil is a decentralized end-to-end-encrypted IPv6 mesh (200::/7) with no
// coordination server and stable key-derived addressing. Unlike wgbridge there
// is no wireguard device: the yggdrasil core routes overlay packets between its
// ipv6rwc pipe and the configured underlay peers (tcp/tls/quic host sockets that
// ride the device's default route). A pair of pump goroutines bridges the
// userspace netstack TUN ↔ ipv6rwc so a connection dials straight through the
// mesh with no VpnService — the same userspace model as wgbridge/nbbridge.
//
// The shape of the public API is constrained by gomobile's binding rules:
//   - Exported types must be structs, not interfaces.
//   - Exported methods can only take/return: primitives, strings, []byte,
//     error, pointers to other exported struct types.
//   - Read-style methods return a fresh []byte instead of filling a caller
//     buffer because gomobile copies []byte parameters across the JNI boundary.
//
// A tunnel is brought up with [StartTunnel], passing a yggdrasil NodeConfig JSON
// (PrivateKey hex, Peers, Listen, IfMTU, GroupPassword, AllowedPublicKeys,
// NodeInfo). An empty PrivateKey generates a fresh identity; persist the config
// (or [TunnelHandle.PublicKey]) to keep the mesh address stable across restarts.
// Callers then [TunnelHandle.Dial] to obtain a [Conn] whose Read/Write go through
// the userspace netstack and out over the mesh.
package yggbridge

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	"github.com/yggdrasil-network/yggdrasil-go/src/ipv6rwc"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/socks5"
)

// maxPacket is the buffer size for the mesh pipe. core.ReadFrom and
// ipv6rwc.Read truncate into smaller buffers, so always read into a buffer at
// least as large as the core MTU (65535).
const maxPacket = 65535

// TunnelHandle is a live Yggdrasil mesh node backed by a userspace netstack TUN
// bridged to the yggdrasil ipv6rwc pipe. Not safe for concurrent Close, but Dial
// is safe to call concurrently.
type TunnelHandle struct {
	core   *core.Core
	rwc    *ipv6rwc.ReadWriteCloser
	tun    tun.Device
	tnet   *netstack.Net
	addr   netip.Addr // key-derived mesh /128; the bind address for listeners
	socksLn net.Listener
	mu     sync.Mutex
	closed bool
	wg     sync.WaitGroup
}

// Conn is a TCP connection through a [TunnelHandle]. Bound to gomobile; Read
// returns a fresh byte slice of up to size bytes because gomobile passes []byte
// arguments by copy.
type Conn struct {
	c net.Conn
}

// RemoteAddrHost returns the literal IP this connection was established to, or
// "" when unknown. Mirrors wgbridge/tsbridge so every backend exposes the
// resolved peer identically.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// UDPConn is an unconnected UDP socket inside the tunnel's gVisor netstack.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result for gomobile, which only supports
// single-value-plus-error returns. Empty Data + non-nil error signals timeout
// (net.Error.Timeout()) or transport failure; callers translate the timeout case
// to "return nil" (Mosh) or equivalent.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// StartTunnel parses a yggdrasil NodeConfig JSON and brings a mesh node up behind
// a userspace netstack. The returned handle must be closed via
// [TunnelHandle.Close]. Multicast discovery is intentionally not started (manual
// peers only); this keeps the platform interface-enumeration deps out of the
// embedded binary.
func StartTunnel(configJSON string) (*TunnelHandle, error) {
	cfg := config.GenerateConfig()
	if err := cfg.UnmarshalHJSON([]byte(configJSON)); err != nil {
		return nil, fmt.Errorf("parse yggdrasil config: %w", err)
	}
	cfg.MulticastInterfaces = nil

	// Mirror contrib/mobile's option building: never peer *to* a mesh address
	// (200::/7) — underlay peers must be reachable transport endpoints.
	iprange := net.IPNet{IP: net.ParseIP("200::"), Mask: net.CIDRMask(7, 128)}
	options := []core.SetupOption{
		core.NodeInfo(cfg.NodeInfo),
		core.NodeInfoPrivacy(cfg.NodeInfoPrivacy),
		core.PeerFilter(func(ip net.IP) bool { return !iprange.Contains(ip) }),
	}
	for _, peer := range cfg.Peers {
		options = append(options, core.Peer{URI: peer})
	}
	if cfg.GroupPassword != "" {
		options = append(options, core.GroupPassword(cfg.GroupPassword))
	}
	for _, allowed := range cfg.AllowedPublicKeys {
		k, err := hex.DecodeString(allowed)
		if err != nil {
			return nil, fmt.Errorf("decode allowed public key %q: %w", allowed, err)
		}
		options = append(options, core.AllowedPublicKey(k))
	}
	for _, lAddr := range cfg.Listen {
		options = append(options, core.ListenAddress(lAddr))
	}

	c, err := core.New(cfg.Certificate, nil, options...)
	if err != nil {
		return nil, fmt.Errorf("start yggdrasil core: %w", err)
	}

	rwc := ipv6rwc.NewReadWriteCloser(c)
	addr, ok := netip.AddrFromSlice(c.Address())
	if !ok {
		c.Stop()
		return nil, errors.New("yggdrasil core returned no mesh address")
	}
	addr = addr.Unmap()

	// Keep the netstack MTU and the mesh pipe MTU equal: ipv6rwc advertises its
	// MTU in ICMPv6 Packet-Too-Big to mesh senders, so it must match what the
	// app stack can actually receive. Floor at the IPv6 minimum (1280).
	mtu := cfg.IfMTU
	if mtu < 1280 {
		mtu = 1280
	}
	if mtu > rwc.MaxMTU() {
		mtu = rwc.MaxMTU()
	}

	// dns nil: mesh destinations are always IPv6 literals; netstack short-circuits
	// IP-literal lookups. Hostname dials are not supported over the mesh.
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{addr}, nil, int(mtu))
	if err != nil {
		c.Stop()
		return nil, fmt.Errorf("create netstack TUN: %w", err)
	}
	rwc.SetMTU(mtu)

	t := &TunnelHandle{core: c, rwc: rwc, tun: tunDev, tnet: tnet, addr: addr}
	t.wg.Add(2)
	go t.pumpOut()
	go t.pumpIn()
	return t, nil
}

// pumpOut drains packets the app SENT out of the netstack TUN and writes them to
// the mesh. tun.Read yields outbound packets (netstack WriteNotify); ipv6rwc.Write
// rejects link-local noise (DAD neighbor solicitations from ::, router
// solicitations, MLD reports) and non-mesh destinations — those errors are
// expected and must not stop the pump (mirrors yggdrasil src/tun/iface.go).
func (t *TunnelHandle) pumpOut() {
	defer t.wg.Done()
	bufs := [][]byte{make([]byte, maxPacket)}
	sizes := make([]int, 1)
	for {
		n, err := t.tun.Read(bufs, sizes, 0)
		if err != nil {
			return // os.ErrClosed once the TUN is closed
		}
		for i := 0; i < n; i++ {
			_, _ = t.rwc.Write(bufs[i][:sizes[i]])
		}
	}
}

// pumpIn reads packets FROM the mesh and injects them into the netstack TUN for
// the app. ipv6rwc.Read already filters to packets addressed to us and
// synthesizes ICMPv6 Packet-Too-Big for oversize inbound packets.
func (t *TunnelHandle) pumpIn() {
	defer t.wg.Done()
	buf := make([]byte, maxPacket)
	for {
		n, err := t.rwc.Read(buf)
		if err != nil {
			return // core closed
		}
		if n == 0 {
			continue
		}
		if _, err := t.tun.Write([][]byte{buf[:n]}, 0); err != nil {
			return
		}
	}
}

// Dial opens a TCP connection through the mesh. timeoutMs <= 0 uses a generous
// default so callers don't have to special-case "no timeout". host must be a mesh
// IPv6 literal (no DNS over the mesh).
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
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	c, err := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// ListenUDP opens an unconnected UDP socket inside the netstack, bound to the
// node's mesh address on an ephemeral port. Binding the concrete mesh address
// (not the unspecified address) is required: gVisor refuses to route from an
// unspecified source and SIGABRTs on a zero AddrPort (cf. wgbridge).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	addr := t.addr
	t.mu.Unlock()

	if !addr.IsValid() {
		return nil, errors.New("tunnel has no mesh address for UDP bind")
	}
	laddr := netip.AddrPortFrom(addr, 0)
	pc, err := tnet.ListenUDPAddrPort(laddr)
	if err != nil {
		return nil, fmt.Errorf("netstack ListenUDP %s: %w", laddr, err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting this
// tunnel and returns its bound TCP port. Idempotent. Closing the tunnel tears
// the listener down. Used by transports that can't be intercepted at the Kotlin
// Socket layer (rclone via HTTPS_PROXY, IronRDP via a vendored SOCKS5 client).
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

// LocalAddress returns the node's mesh IPv6 address (the key-derived /128) —
// both the bind host for [ListenTCP] and the host a peer dials to reach a server
// bound on it.
func (t *TunnelHandle) LocalAddress() string {
	if t.addr.IsValid() {
		return t.addr.String()
	}
	return ""
}

// Subnet returns the node's routed /64 subnet in CIDR notation, for diagnostics.
func (t *TunnelHandle) Subnet() string {
	subnet := t.core.Subnet()
	return subnet.String()
}

// PublicKey returns the node's public key in hex form. Persist it (or the config
// PrivateKey) to keep the mesh address stable across restarts.
func (t *TunnelHandle) PublicKey() string {
	return hex.EncodeToString(t.core.PublicKey())
}

// Listener accepts inbound TCP connections on the node's mesh address inside the
// gVisor netstack. Closing the tunnel tears down the netstack, so a pending
// Accept then returns an error.
type Listener struct {
	ln net.Listener
}

// ListenTCP binds a TCP listener on the node's mesh address. Lets an on-device
// server accept connections from mesh peers at <mesh-ipv6>:port.
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	addr := netip.AddrPortFrom(t.addr, uint16(port))
	t.mu.Unlock()

	ln, err := tnet.ListenTCPAddrPort(addr)
	if err != nil {
		return nil, fmt.Errorf("netstack ListenTCP %s: %w", addr, err)
	}
	return &Listener{ln: ln}, nil
}

// Accept blocks until a peer connects, returning the connection wrapped as a
// [Conn]. A non-nil error means the listener (or tunnel) was closed.
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

// Close tears down the tunnel. Idempotent. The core is closed first so pumpIn's
// Read errors out and underlay links shut down; pumpOut keeps draining the TUN
// (rwc.Write fails fast) until tun.Close stops new emissions, minimizing the
// netstack close race inherent to wireguard-go's netstack (wgbridge has it too).
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
	if t.rwc != nil {
		t.rwc.Close() // core.Close + core.Stop
	}
	if t.tun != nil {
		t.tun.Close()
	}
}

// Read returns up to size bytes from the connection. A nil slice with a non-nil
// error signals EOF or a transport failure; callers translate to their platform's
// "end of stream" convention (e.g. -1 in Java).
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

// Write writes all of data.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// ReadFrom blocks until a datagram arrives or the deadline expires. timeoutMs <= 0
// blocks indefinitely; >0 sets a one-shot read deadline. On timeout returns a
// net.Error with .Timeout() true.
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
		return &UDPRead{Data: buf[:n], FromHost: addr.String(), FromPort: 0}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
}

// WriteTo sends data to host:port through the mesh. host must be a literal IP.
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