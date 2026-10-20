// Package ocbridge is a userspace Cisco AnyConnect (and ocserv/OpenConnect
// server) client exposed via gomobile, so Haven can route a single
// connection profile through a corporate AnyConnect VPN without Cisco's app
// and without taking the system VPN slot — the same per-app model as
// wgbridge (WireGuard), tsbridge (Tailscale) and nbbridge (NetBird).
//
// Protocol: Cisco's "aggregate" XML authentication over HTTPS yields a
// session cookie; a CONNECT /CSCOSSLC/tunnel on a fresh TLS connection then
// carries IP packets as CSTP frames ("STF\x01" + length + type). This
// follows openconnect's anyconnect protocol driver. The IP packets are fed
// to wireguard-go's gVisor netstack (already linked into libgojni for
// wgbridge), which is what Dial / ListenUDP / ListenTCP talk to, so no
// kernel TUN or VpnService is involved.
//
// Scope, deliberately: TLS (CSTP) only — no DTLS, no compression, no
// client certificates, no SAML/SSO, no posture (CSD/HostScan). Username +
// password, auth-group selection, and a TOTP or fixed second factor are
// supported. Sessions reconnect on their own after network changes.
//
// The exported API mirrors wgbridge so the Kotlin wrapper is the same shape.
package ocbridge

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/socks5"
)

// TunnelHandle is a live AnyConnect session with its userspace netstack.
// Dial is safe to call concurrently.
type TunnelHandle struct {
	sess     *session
	tnet     *netstack.Net
	bindAddr netip.Addr
	key      string // session-cache key

	mu      sync.Mutex
	closed  bool
	failure error
	socksLn net.Listener
}

// Conn is a TCP connection through a [TunnelHandle]. Read returns a fresh
// slice because gomobile passes []byte arguments by copy.
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

// StartTunnel is [StartTunnelWithPrompter] without a prompter: questions
// the config can't answer fail the sign-in.
func StartTunnel(configJSON string) (*TunnelHandle, error) {
	return StartTunnelWithPrompter(configJSON, nil)
}

// StartTunnelWithPrompter brings the tunnel up. If this process still holds
// the cookie of an earlier session for the same gateway, user and group
// (see [TunnelHandle.Close]), it resumes that session first — no password,
// no SMS — and only signs in afresh when the gateway no longer accepts it.
// Questions the config can't answer (an SMS code, a password that isn't
// stored) go to p. Blocks while the user answers; call from a background
// thread. Close the handle with [TunnelHandle.Close] or [TunnelHandle.Logout].
func StartTunnelWithPrompter(configJSON string, p Prompter) (*TunnelHandle, error) {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	base, err := normalizeServer(cfg.Server)
	if err != nil {
		return nil, err
	}
	tlsConf := tlsConfigFor(cfg, base)
	key := sessionKey(cfg, base)

	var (
		cookie string
		conn   *tls.Conn
		br     *bufio.Reader
		params *tunnelParams
	)
	if cached := sessions.get(key); cached != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		conn, br, params, err = connectCSTP(ctx, cfg, base, tlsConf, cached)
		cancel()
		switch {
		case err == nil:
			cookie = cached
		case errors.Is(err, errCookieRejected):
			sessions.forget(key) // expired on the gateway: sign in below
		default:
			// Gateway unreachable (offline, roaming). Signing in would fail
			// the same way, and for SMS users would only waste a code.
			return nil, fmt.Errorf("open tunnel: %w", err)
		}
	}
	if cookie == "" {
		cookie, err = authenticate(cfg, base, tlsConf, p)
		if err != nil {
			return nil, fmt.Errorf("authenticate: %w", err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		conn, br, params, err = connectCSTP(ctx, cfg, base, tlsConf, cookie)
		cancel()
		if err != nil {
			go logout(cfg, base, tlsConf, cookie)
			return nil, fmt.Errorf("open tunnel: %w", err)
		}
	}
	sessions.put(key, cookie)

	dev, tnet, err := netstack.CreateNetTUN(params.addresses(), params.dns, params.mtu)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("create netstack: %w", err)
	}

	h := &TunnelHandle{tnet: tnet, key: key}
	if params.addr4.IsValid() {
		h.bindAddr = params.addr4
	} else {
		h.bindAddr = params.addr6
	}
	h.sess = newSession(cfg, base, tlsConf, cookie, conn, br, params, dev, func(err error) {
		// The session can't continue (credentials no longer accepted,
		// gateway terminated it): its cookie is worthless now.
		sessions.forget(key)
		h.closeWith(err, false)
	})
	h.sess.prompter = p
	h.sess.start()
	return h, nil
}

// EndSession signs out a session this process kept resumable for the given
// config but which no handle is using (the user tapped "Sign out" on an idle
// tunnel, or edited / deleted it). Best-effort and quick: one HTTPS request.
func EndSession(configJSON string) {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return
	}
	base, err := normalizeServer(cfg.Server)
	if err != nil {
		return
	}
	key := sessionKey(cfg, base)
	if cookie := sessions.get(key); cookie != "" {
		sessions.forget(key)
		logout(cfg, base, tlsConfigFor(cfg, base), cookie)
	}
}

// HasResumableSession reports whether a later start for this config would
// try to resume instead of signing in. For the UI.
func HasResumableSession(configJSON string) bool {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return false
	}
	base, err := normalizeServer(cfg.Server)
	if err != nil {
		return false
	}
	return sessions.get(sessionKey(cfg, base)) != ""
}

// sessions caches session cookies in memory only: they are bearer tokens,
// and the process staying alive is what "stay connected" relies on anyway
// (Haven's foreground service). Keyed by gateway, user and group so editing
// any of those starts a fresh session.
var sessions = &sessionCache{m: map[string]string{}}

type sessionCache struct {
	mu sync.Mutex
	m  map[string]string
}

func (c *sessionCache) get(k string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.m[k]
}

func (c *sessionCache) put(k, v string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.m[k] = v
}

func (c *sessionCache) forget(k string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.m, k)
}

func sessionKey(cfg *Config, base *url.URL) string {
	return base.String() + "\x00" + strings.ToLower(cfg.Username) + "\x00" + strings.ToLower(cfg.AuthGroup)
}

func (t *TunnelHandle) net() (*netstack.Net, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		if t.failure != nil {
			return nil, fmt.Errorf("tunnel closed: %w", t.failure)
		}
		return nil, errors.New("tunnel closed")
	}
	return t.tnet, nil
}

// Dial opens a TCP connection through the tunnel. Hostnames resolve via
// the DNS servers the gateway pushed. timeoutMs <= 0 means 30 s.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	tnet, err := t.net()
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
// (netstack can't route from an unspecified source; see wgbridge).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	tnet, err := t.net()
	if err != nil {
		return nil, err
	}
	pc, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(t.bindAddr, 0))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenUDP: %w", err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting the
// tunnel (for rclone / IronRDP) and returns its port. Idempotent.
func (t *TunnelHandle) StartSocksListener() (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return 0, errors.New("tunnel closed")
	}
	if t.socksLn != nil {
		return t.socksLn.Addr().(*net.TCPAddr).Port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("bind SOCKS5 listener: %w", err)
	}
	t.socksLn = ln
	tnet := t.tnet
	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// BindAddr is the address the gateway assigned (IPv4 preferred).
func (t *TunnelHandle) BindAddr() string {
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// ListenTCP binds a TCP listener on the tunnel address. Most AnyConnect
// gateways filter inbound connections to clients, but where policy allows
// it this lets on-device servers (MCP) be reached over the VPN.
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	tnet, err := t.net()
	if err != nil {
		return nil, err
	}
	ln, err := tnet.ListenTCPAddrPort(netip.AddrPortFrom(t.bindAddr, uint16(port)))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenTCP: %w", err)
	}
	return &Listener{ln: ln}, nil
}

// LastError is why the tunnel went down on its own, or "" if it is up or
// was closed normally. For surfacing in the UI.
func (t *TunnelHandle) LastError() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.failure != nil {
		return t.failure.Error()
	}
	return ""
}

// Close tears down the netstack and drops the connection but leaves the
// gateway session alive, so the next start for the same config resumes it
// without a password or second factor — as long as the gateway still holds
// it (its idle timeout). This is what spares SMS users a new code every
// time they reconnect. Use [TunnelHandle.Logout] to end it. Idempotent.
func (t *TunnelHandle) Close() {
	t.closeWith(nil, false)
}

// Logout is Close plus ending the session on the gateway; the next start
// signs in afresh. For an explicit "Sign out". Idempotent.
func (t *TunnelHandle) Logout() {
	t.closeWith(nil, true)
}

// Revive checks the connection after a network change or a return to the
// foreground and reconnects at once if it's dead (see session.revive).
// Cheap and non-blocking.
func (t *TunnelHandle) Revive() {
	t.mu.Lock()
	closed := t.closed
	t.mu.Unlock()
	if !closed {
		t.sess.revive()
	}
}

func (t *TunnelHandle) closeWith(cause error, logoutToo bool) {
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
		ln.Close()
	}
	cookie := t.sess.close(logoutToo)
	switch {
	case logoutToo || cause != nil:
		sessions.forget(t.key)
	default:
		sessions.put(t.key, cookie) // latest cookie (re-login may have changed it)
	}
	t.sess.dev.Close()
}

// --- Conn / UDPConn / Listener (same contract as wgbridge) ------------------

// RemoteAddrHost returns the literal IP this connection reached.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// Read returns up to size bytes; nil + error on EOF or failure.
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

// Close closes the connection.
func (c *Conn) Close() error { return c.c.Close() }

// ReadFrom blocks for a datagram; timeoutMs > 0 sets a one-shot deadline
// and a timeout surfaces as an "i/o timeout" error.
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
