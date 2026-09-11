// Package nbbridge provides a minimal Go bridge over NetBird's client/embed
// library, exposed via gomobile for Haven's per-connection NetBird support
// (#492).
//
// Shares the public API shape with [tsbridge] and [wgbridge] — StartTunnel →
// TunnelHandle → Dial → Conn → Read/Write/Close — so the Kotlin side can treat
// all three backends uniformly through [sh.haven.core.tunnel.Tunnel].
//
// Why embed rather than the NetBird app: client/embed runs a userspace
// netstack, so a connection dials straight through it with no VpnService and
// no second app installed. That is the same property tsnet gives us for
// Tailscale, and it is the whole reason #492 was worth doing.
//
// Licence note: client/embed is BSD-3-Clause. The AGPL parts of the NetBird
// repo (management/, signal/, relay/, combined/) are server components and are
// not linked here.
//
// Differences from tsbridge worth noting:
//   - Auth is a setup key rather than an authkey. Confirmed with the reporter
//     on #492 that setup key, not SSO/JWT, is the mode to support first; the
//     Options struct also carries JWTToken and PrivateKey if that changes.
//   - ManagementURL is a plain option, so self-hosted and hosted NetBird are
//     the same code path — no separate backend.
//   - ListenTCP/ListenUDP ignore the host part of the address they are given
//     and always bind the client's own overlay address, so only the port is
//     meaningful. tsnet makes the caller choose between its v4 and v6.
package nbbridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	netbird "github.com/netbirdio/netbird/client/embed"

	"sh.haven/rcbridge/socks5"
)

// TunnelHandle is a running NetBird client. Mirrors tsbridge.TunnelHandle.
type TunnelHandle struct {
	c       *netbird.Client
	mu      sync.Mutex
	closed  bool
	socksLn net.Listener
}

// Conn is a TCP connection through the tunnel. Mirrors tsbridge.Conn so the
// Kotlin adapter can treat both the same way.
type Conn struct {
	c net.Conn
}

// UDPConn is an unconnected UDP socket on the overlay. Mirrors tsbridge.UDPConn.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result. See tsbridge.UDPRead.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// Listener accepts inbound connections on the overlay address. Used by the MCP
// endpoint (#176) so a peer can reach a server on the device at an address that
// survives the phone's WiFi/hotspot roams.
type Listener struct {
	ln net.Listener
}

// startTimeout bounds StartTunnel. Joining takes a management handshake, a
// signal connection and the first peer sync; on a phone behind a hostile NAT
// that is not instant. Matches the 60s tsbridge allows for the same reasons.
const startTimeout = 60 * time.Second

// StartTunnel joins a NetBird network with the given setup key and state
// directory.
//
// stateDir must be an absolute path the app owns — typically
// context.filesDir/netbird-<configId>/. Both the config and state files live
// under it, so each Haven connection gets its own identity rather than sharing
// one device-wide peer.
//
// hostname is this peer's name in the network; blank picks a default.
//
// managementURL points at a self-hosted NetBird management server. Empty keeps
// NetBird's hosted default, so "both hosted and self-hosted" from #492 costs
// nothing extra.
//
// Blocks until the client has started, so a subsequent Dial has a netstack to
// dial through.
func StartTunnel(setupKey, stateDir, hostname, managementURL string) (*TunnelHandle, error) {
	if setupKey == "" {
		return nil, errors.New("setup key required")
	}
	if stateDir == "" {
		return nil, errors.New("state directory required")
	}
	if hostname == "" {
		hostname = "haven-android"
	}

	c, err := netbird.New(netbird.Options{
		DeviceName:    hostname,
		SetupKey:      setupKey,
		ManagementURL: managementURL, // empty = NetBird's hosted default
		ConfigPath:    stateDir + "/config.json",
		StatePath:     stateDir + "/state.json",
		// Userspace netstack is the default and is what lets us dial without a
		// VpnService; NoUserspace would need privileges Haven does not have.
	})
	if err != nil {
		return nil, fmt.Errorf("create netbird client: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	defer cancel()
	if err := c.Start(ctx); err != nil {
		return nil, fmt.Errorf("join netbird network: %w", err)
	}

	return &TunnelHandle{c: c}, nil
}

// client returns the live client, or an error once closed.
func (t *TunnelHandle) client() (*netbird.Client, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return nil, errors.New("tunnel closed")
	}
	return t.c, nil
}

// Dial opens a TCP connection to host:port across the overlay.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	c, err := t.client()
	if err != nil {
		return nil, err
	}
	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()
	conn, err := c.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d via netbird: %w", host, port, err)
	}
	return &Conn{c: conn}, nil
}

// ListenUDP opens an unconnected UDP socket on the overlay, bound to this
// peer's own address on an ephemeral port. Packets traverse the overlay rather
// than the device's default route — the point of #164 for Mosh.
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	c, err := t.client()
	if err != nil {
		return nil, err
	}
	// The host is ignored — ListenUDP always binds the client's own overlay
	// address — but the API still parses host:port, so it has to be well formed.
	pc, err := c.ListenUDP("0.0.0.0:0")
	if err != nil {
		return nil, fmt.Errorf("netbird ListenUDP: %w", err)
	}
	return &UDPConn{c: pc}, nil
}

// ListenTCP binds a TCP listener on this peer's overlay address so peers can
// reach a server running on the device (#176).
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	c, err := t.client()
	if err != nil {
		return nil, err
	}
	ln, err := c.ListenTCP(net.JoinHostPort("0.0.0.0", strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("netbird ListenTCP :%d: %w", port, err)
	}
	return &Listener{ln: ln}, nil
}

// LocalAddress returns this peer's overlay IPv4, the address a peer dials to
// reach a server bound by ListenTCP. Empty before the network assigns one.
func (t *TunnelHandle) LocalAddress() (string, error) {
	c, err := t.client()
	if err != nil {
		return "", err
	}
	st, err := c.Status()
	if err != nil {
		return "", fmt.Errorf("netbird status: %w", err)
	}
	return st.LocalPeerState.IP, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting this
// overlay and returns its bound TCP port. Idempotent; closing the tunnel tears
// the listener down. Mirrors tsbridge's equivalent so the same Kotlin caller
// works against either backend.
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
	c := t.c
	t.mu.Unlock()

	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})

	return ln.Addr().(*net.TCPAddr).Port, nil
}

// Close leaves the network. The state directory is kept intact so a subsequent
// StartTunnel reuses the identity rather than consuming the setup key again.
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
	if t.c != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		_ = t.c.Stop(ctx)
		cancel()
		t.c = nil
	}
}

// Accept blocks until a peer connects. Mirrors tsbridge/wgbridge accept shape.
func (l *Listener) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Close stops accepting. Idempotent.
func (l *Listener) Close() error {
	return l.ln.Close()
}

// Read returns up to size bytes. Signals EOF the same way tsbridge and wgbridge
// do (nil slice + io.EOF) so one Kotlin InputStream wrapper serves all three.
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}

// Write writes all of data. Gomobile copies []byte across JNI, so the caller's
// array is not mutated here.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// ReadFrom matches tsbridge.UDPConn.ReadFrom — same timeout semantics
// (timeoutMs <= 0 blocks; >0 sets a one-shot read deadline) and the same
// gomobile-friendly return shape.
func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		if err := u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)); err != nil {
			return nil, err
		}
	} else {
		if err := u.c.SetReadDeadline(time.Time{}); err != nil {
			return nil, err
		}
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	host, portStr, splitErr := net.SplitHostPort(addr.String())
	if splitErr != nil {
		return nil, fmt.Errorf("parse source %s: %w", addr, splitErr)
	}
	port, convErr := strconv.Atoi(portStr)
	if convErr != nil {
		return nil, fmt.Errorf("parse source port %s: %w", portStr, convErr)
	}
	return &UDPRead{Data: buf[:n], FromHost: host, FromPort: port}, nil
}

// WriteTo sends data to host:port across the overlay.
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return fmt.Errorf("resolve %s:%d: %w", host, port, err)
	}
	_, err = u.c.WriteTo(data, addr)
	return err
}

// Close closes the socket. Idempotent.
func (u *UDPConn) Close() error {
	return u.c.Close()
}
