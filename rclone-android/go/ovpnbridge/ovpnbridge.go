// Package ovpnbridge provides a minimal Go bridge over OpenVPN3 Core
// (openvpn3-core/adapter.cpp + the vendored go-openvpn cgo glue), exposed
// via gomobile for Haven's per-app OpenVPN feature. Mirrors wgbridge's
// shape (StartTunnel / TunnelHandle / Conn) so both tunnel backends look
// the same to gomobile and to Kotlin — the two differ only in how the
// tunnel comes up: wgbridge drives wireguard-go's own device.Device
// directly, ovpnbridge drives OpenVPN3 Core through a TunnelSetup callback
// (see tunnelsetup.go) that hands core a socketpair fd pumped into a gVisor
// netstack (see pump.go).
package ovpnbridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/ovpnbridge/openvpn3"
	"sh.haven/rcbridge/socks5"
)

// establishTimeout bounds how long StartTunnel waits for OpenVPN3 core to
// either establish the tunnel or fail outright (bad credentials, TLS
// handshake failure, unreachable server, ...). 30s matches Config's own
// ConnTimeout default (see openvpn3.NewConfig) with headroom for the
// establish-side Go/cgo work on top of core's own connect attempt.
const establishTimeout = 30 * time.Second

// TunnelHandle is a live OpenVPN tunnel backed by OpenVPN3 Core's TLS/data
// channel and a userspace TUN + gVisor netstack. Structurally parallel to
// wgbridge.TunnelHandle — same Dial/StartSocksListener/BindAddr/Close
// shape — so Kotlin's OpenVpnTunnel and WireguardTunnel can share a common
// pattern despite the different native session underneath.
type TunnelHandle struct {
	session *openvpn3.Session
	setup   *OvpnTunnelSetup
	tnet    *netstack.Net

	mu      sync.Mutex
	closed  bool
	socksLn net.Listener

	bindAddr netip.Addr
}

// Conn is a TCP connection through a TunnelHandle. Bound to gomobile; Read
// returns a fresh byte slice because gomobile passes []byte by copy.
type Conn struct {
	c net.Conn
}

// RemoteAddrHost returns the literal IP this connection was established
// to, or "" when unknown. Mirrors wgbridge.Conn.RemoteAddrHost.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// ovpnEvents implements openvpn3.MobileSessionCallbacks. OnEvent's only job
// is to unblock StartTunnel early on a fatal/error event that arrives
// before OpenVPN3 core ever reaches Establish() (bad auth, TLS failure,
// ...) — without it, StartTunnel would sit out the full establishTimeout
// on a connection that already failed.
type ovpnEvents struct {
	setup *OvpnTunnelSetup
}

func (e *ovpnEvents) OnEvent(ev openvpn3.Event) {
	// Logged unconditionally, not just fatal/error ones -- the normal
	// progression (RESOLVE, WAIT, CONNECTING, GET_CONFIG, ASSIGN_IP,
	// ADD_ROUTES, CONNECTED) is exactly what you need on-device to tell
	// "handshake never started", "TLS failed", and "handshake succeeded
	// but the data channel isn't passing traffic" apart -- symptoms that
	// otherwise all look identical from the Kotlin side (StartTunnel
	// either returns a handle or it doesn't).
	androidLog("ovpn3-event", fmt.Sprintf("%s (error=%v fatal=%v): %s", ev.Name, ev.Error, ev.Fatal, ev.Info))
	if ev.Fatal || ev.Error {
		e.setup.notifyReady(nil, netip.Addr{}, fmt.Errorf("openvpn event %s: %s", ev.Name, ev.Info))
	}
}

func (e *ovpnEvents) Log(text string) {
	androidLog("ovpn3", text)
}

func (e *ovpnEvents) OnStats(openvpn3.Statistics) {}

var _ openvpn3.MobileSessionCallbacks = (*ovpnEvents)(nil)

// StartTunnel parses an .ovpn profile and brings a tunnel up, blocking
// until OpenVPN3 core either establishes the tunnel or fails. The returned
// handle must be closed via [TunnelHandle.Close].
func StartTunnel(configText, username, password string) (*TunnelHandle, error) {
	config := openvpn3.NewConfig(configText)
	// NewConfig defaults DisableClientCert to true, which silently breaks
	// any mutual-TLS server (the common case) by omitting the client cert
	// entirely from the TLS handshake.
	config.DisableClientCert = false
	config.CompressionMode = "no"

	creds := openvpn3.UserCredentials{Username: username, Password: password}
	setup := newOvpnTunnelSetup()
	events := &ovpnEvents{setup: setup}

	session := openvpn3.NewMobileSession(config, creds, events, setup)
	session.Start()

	// session.Wait() blocks until the WHOLE session ends (connect_run keeps
	// running for the tunnel's entire lifetime), not until connected — so a
	// session that fails before ever calling connect() (e.g. new_session
	// itself rejected a malformed profile) would otherwise never unblock
	// setup.ready and we'd sit out the full establishTimeout on a config
	// that was invalid from the start. Race it against setup.ready instead.
	waitDone := make(chan error, 1)
	go func() { waitDone <- session.Wait() }()

	select {
	case result := <-setup.ready:
		if result.err != nil {
			session.Stop()
			return nil, fmt.Errorf("openvpn establish: %w", result.err)
		}
		return &TunnelHandle{
			session:  session,
			setup:    setup,
			tnet:     result.tnet,
			bindAddr: result.bindAddr,
		}, nil
	case err := <-waitDone:
		if err == nil {
			err = errors.New("session ended before establishing a tunnel")
		}
		return nil, fmt.Errorf("openvpn establish: %w", err)
	case <-time.After(establishTimeout):
		session.Stop()
		return nil, fmt.Errorf("openvpn establish: timed out after %s", establishTimeout)
	}
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
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	c, err := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting this
// tunnel and returns its bound TCP port. Idempotent. Mirrors
// wgbridge.TunnelHandle.StartSocksListener exactly (same transports rely on
// a single SOCKS5 endpoint shape regardless of which tunnel backend is
// active underneath).
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

// BindAddr returns the tunnel's server-pushed interface IP (the first
// IPv4 AddAddress, or the first address overall), as reported to
// OvpnTunnelSetup.Establish. Empty if unset.
func (t *TunnelHandle) BindAddr() string {
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// Close tears down the tunnel. Idempotent.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return
	}
	t.closed = true
	if t.socksLn != nil {
		t.socksLn.Close()
		t.socksLn = nil
	}
	t.mu.Unlock()

	t.setup.Teardown(true)
	t.session.Stop()
}

// Read returns up to size bytes from the connection. A nil slice with a
// non-nil error signals EOF or a transport failure.
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
