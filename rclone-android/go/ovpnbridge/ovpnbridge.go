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
// wgbridge.TunnelHandle — same Dial/ListenUDP/ListenTCP/StartSocksListener/
// BindAddr/LastError/Close shape — so Kotlin's OpenVpnTunnel, WireguardTunnel,
// and AnyConnectTunnel share the same contract.
type TunnelHandle struct {
	session *openvpn3.Session
	setup   *OvpnTunnelSetup
	tnet    *netstack.Net

	mu      sync.Mutex
	closed  bool
	failure error
	socksLn net.Listener

	bindAddr netip.Addr
}

// Conn is a TCP connection through a TunnelHandle. Bound to gomobile; Read
// returns a fresh byte slice because gomobile passes []byte by copy.
type Conn struct {
	c net.Conn
}

// UDPConn is an unconnected UDP socket inside the tunnel's netstack.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result for gomobile.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// Listener accepts inbound TCP connections on the tunnel address.
type Listener struct {
	ln net.Listener
}

// RemoteAddrHost returns the literal IP this connection was established
// to, or "" when unknown. Mirrors wgbridge.Conn.RemoteAddrHost.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// ovpnEvents implements openvpn3.MobileSessionCallbacks. OnEvent unblocks
// StartTunnel early on a fatal/error event that arrives before OpenVPN3
// core ever reaches Establish() (bad auth, TLS failure, ...), and after
// connect marks the live TunnelHandle dead if a Fatal event terminates the
// session.
type ovpnEvents struct {
	setup   *OvpnTunnelSetup
	connect chan error

	mu     sync.Mutex
	handle *TunnelHandle
}

func (e *ovpnEvents) setHandle(h *TunnelHandle) {
	e.mu.Lock()
	e.handle = h
	e.mu.Unlock()
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
		err := fmt.Errorf("openvpn event %s: %s", ev.Name, ev.Info)
		e.setup.notifyReady(nil, netip.Addr{}, err)
		select {
		case e.connect <- err:
		default:
		}
		if ev.Fatal {
			e.mu.Lock()
			h := e.handle
			e.mu.Unlock()
			if h != nil {
				h.closeWith(err)
			}
		}
		return
	}
	if ev.Name == "CONNECTED" {
		select {
		case e.connect <- nil:
		default:
		}
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
	events := &ovpnEvents{setup: setup, connect: make(chan error, 1)}

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

	timer := time.NewTimer(establishTimeout)
	defer timer.Stop()
	var result establishResult
	select {
	case result = <-setup.ready:
		if result.err != nil {
			session.Stop()
			return nil, fmt.Errorf("openvpn establish: %w", result.err)
		}
	case err := <-waitDone:
		if err == nil {
			err = errors.New("session ended before establishing a tunnel")
		}
		return nil, fmt.Errorf("openvpn establish: %w", err)
	case <-timer.C:
		session.Stop()
		return nil, fmt.Errorf("openvpn establish: timed out after %s", establishTimeout)
	}

	// Establish() only means OpenVPN has created the virtual interface. Core
	// still has to finish bringing up the data channel; dialing at this point
	// can silently black-hole traffic until our caller's timeout expires.
	// Wait until Core explicitly reports CONNECTED before exposing the handle.
	select {
	case err := <-events.connect:
		if err != nil {
			session.Stop()
			return nil, fmt.Errorf("openvpn establish: %w", err)
		}
	case err := <-waitDone:
		if err == nil {
			err = errors.New("session ended before connecting")
		}
		return nil, fmt.Errorf("openvpn establish: %w", err)
	case <-timer.C:
		session.Stop()
		return nil, fmt.Errorf("openvpn establish: timed out after %s waiting for CONNECTED", establishTimeout)
	}

	h := &TunnelHandle{
		session:  session,
		setup:    setup,
		tnet:     result.tnet,
		bindAddr: result.bindAddr,
	}
	events.setHandle(h)
	go func() {
		if err := <-waitDone; err != nil {
			h.closeWith(err)
		} else {
			h.closeWith(errors.New("openvpn session ended"))
		}
	}()
	return h, nil
}

func (t *TunnelHandle) netAndAddr() (*netstack.Net, netip.Addr, error) {
	t.mu.Lock()
	closed := t.closed
	failure := t.failure
	setup := t.setup
	tnet := t.tnet
	bindAddr := t.bindAddr
	t.mu.Unlock()
	if closed {
		if failure != nil {
			return nil, netip.Addr{}, fmt.Errorf("tunnel closed: %w", failure)
		}
		return nil, netip.Addr{}, errors.New("tunnel closed")
	}
	if setup != nil {
		if curNet, curAddr := setup.currentNet(); curNet != nil {
			tnet = curNet
			bindAddr = curAddr
		}
	}
	return tnet, bindAddr, nil
}

// Dial opens a TCP connection through the tunnel. Hostnames resolve via the
// DNS servers pushed by the OpenVPN server. timeoutMs <= 0 uses a 30s default.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	tnet, _, err := t.netAndAddr()
	if err != nil {
		return nil, err
	}

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

// ListenUDP opens an unconnected UDP socket bound to the tunnel address
// (used by Mosh so UDP datagrams traverse the OpenVPN tunnel).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	tnet, bindAddr, err := t.netAndAddr()
	if err != nil {
		return nil, err
	}
	pc, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(bindAddr, 0))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenUDP: %w", err)
	}
	return &UDPConn{c: pc}, nil
}

// ListenTCP binds a TCP listener on the tunnel address inside the userspace
// netstack, allowing tunnel peers to reach on-device listeners (such as MCP).
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	tnet, bindAddr, err := t.netAndAddr()
	if err != nil {
		return nil, err
	}
	ln, err := tnet.ListenTCPAddrPort(netip.AddrPortFrom(bindAddr, uint16(port)))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenTCP: %w", err)
	}
	return &Listener{ln: ln}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting this
// tunnel and returns its bound TCP port. Idempotent. Mirrors
// wgbridge.TunnelHandle.StartSocksListener.
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
	t.mu.Unlock()

	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		tnet, _, err := t.netAndAddr()
		if err != nil {
			return nil, err
		}
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
	if t.setup != nil {
		if _, curAddr := t.setup.currentNet(); curAddr.IsValid() {
			return curAddr.String()
		}
	}
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// LastError returns the fatal reason the tunnel ended on its own, or "" if
// the tunnel is still up or was closed normally.
func (t *TunnelHandle) LastError() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.failure != nil {
		return t.failure.Error()
	}
	return ""
}

// Close tears down the tunnel. Idempotent.
func (t *TunnelHandle) Close() {
	t.closeWith(nil)
}

func (t *TunnelHandle) closeWith(cause error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return
	}
	t.closed = true
	t.failure = cause
	ln := t.socksLn
	t.socksLn = nil
	t.mu.Unlock()

	if ln != nil {
		_ = ln.Close()
	}
	if t.setup != nil {
		t.setup.Teardown(true)
	}
	if t.session != nil {
		t.session.Stop()
	}
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

// ReadFrom blocks for a datagram; timeoutMs > 0 sets a one-shot deadline.
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
	if udp, ok := addr.(*net.UDPAddr); ok {
		return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: addr.String()}, nil
}

// WriteTo sends data to an IP-literal host:port through the tunnel.
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	_, err := u.c.WriteTo(data, &net.UDPAddr{IP: ip, Port: port})
	return err
}

// Close closes the UDP socket.
func (u *UDPConn) Close() error { return u.c.Close() }

// Accept blocks until a peer connects.
func (l *Listener) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Addr returns the bound "ip:port".
func (l *Listener) Addr() string { return l.ln.Addr().String() }

// Close stops accepting.
func (l *Listener) Close() error { return l.ln.Close() }
