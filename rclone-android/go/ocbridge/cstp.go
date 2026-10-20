package ocbridge

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"net/textproto"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/tun"
)

// CSTP packet types (the byte after the length in the 8-byte header).
const (
	pktData       = 0x00
	pktDPDReq     = 0x03
	pktDPDResp    = 0x04
	pktDisconnect = 0x05
	pktKeepalive  = 0x07
	pktCompressed = 0x08
	pktTerminate  = 0x09
)

const (
	hdrLen = 8
	// requestedMTU is what we ask the gateway for. It is the inner MTU of
	// the gateway's tun device; CSTP rides TLS over TCP, so unlike
	// WireGuard there is no outer-path fragmentation to budget for.
	requestedMTU = 1399
	minMTU       = 576
	maxPayload   = 0xffff
)

var errCookieRejected = errors.New("gateway rejected the session cookie")

// tunnelParams are what the gateway assigns in the CONNECT response.
type tunnelParams struct {
	addr4     netip.Addr
	addr6     netip.Addr
	dns       []netip.Addr
	mtu       int
	dpd       time.Duration
	keepalive time.Duration
}

func (p *tunnelParams) addresses() []netip.Addr {
	var out []netip.Addr
	if p.addr4.IsValid() {
		out = append(out, p.addr4)
	}
	if p.addr6.IsValid() {
		out = append(out, p.addr6)
	}
	return out
}

func (p *tunnelParams) sameAddresses(o *tunnelParams) bool {
	return p.addr4 == o.addr4 && p.addr6 == o.addr6
}

// connectCSTP opens the TLS tunnel connection and performs the CONNECT
// handshake. On success the returned reader is positioned at the first
// CSTP frame (it may already hold buffered frame bytes, so it — not the
// raw conn — must be used for all further reads).
func connectCSTP(ctx context.Context, cfg *Config, base *url.URL, tlsConf *tls.Config, cookie string) (*tls.Conn, *bufio.Reader, *tunnelParams, error) {
	d := &tls.Dialer{
		NetDialer: &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second},
		Config:    tlsConf,
	}
	raw, err := d.DialContext(ctx, "tcp", hostPort(base))
	if err != nil {
		return nil, nil, nil, fmt.Errorf("connect %s: %w", hostPort(base), err)
	}
	conn := raw.(*tls.Conn)
	if dl, ok := ctx.Deadline(); ok {
		conn.SetDeadline(dl)
	} else {
		conn.SetDeadline(time.Now().Add(30 * time.Second))
	}

	var req strings.Builder
	req.WriteString("CONNECT /CSCOSSLC/tunnel HTTP/1.1\r\n")
	req.WriteString("Host: " + base.Host + "\r\n")
	req.WriteString("User-Agent: " + cfg.UserAgent + "\r\n")
	req.WriteString("Cookie: webvpn=" + cookie + "\r\n")
	req.WriteString("X-CSTP-Version: 1\r\n")
	req.WriteString("X-CSTP-Hostname: haven-android\r\n")
	req.WriteString("X-CSTP-Base-MTU: 1500\r\n")
	req.WriteString("X-CSTP-MTU: " + strconv.Itoa(requestedMTU) + "\r\n")
	req.WriteString("X-CSTP-Address-Type: IPv6,IPv4\r\n")
	req.WriteString("X-CSTP-Full-IPv6-Capability: true\r\n")
	req.WriteString("X-AnyConnect-Platform: linux-64\r\n")
	req.WriteString("X-Aggregate-Auth: 1\r\n")
	req.WriteString("X-Transcend-Version: 1\r\n")
	// Deliberately no X-DTLS-* (TLS-only; DTLS would need its own UDP
	// socket and gains little for interactive SSH) and no
	// X-CSTP-Accept-Encoding (no compression).
	req.WriteString("\r\n")
	if _, err := io.WriteString(conn, req.String()); err != nil {
		conn.Close()
		return nil, nil, nil, err
	}

	br := bufio.NewReaderSize(conn, 64<<10)
	tp := textproto.NewReader(br)
	status, err := tp.ReadLine()
	if err != nil {
		conn.Close()
		return nil, nil, nil, fmt.Errorf("read CONNECT response: %w", err)
	}
	hdr, err := tp.ReadMIMEHeader()
	if err != nil {
		conn.Close()
		return nil, nil, nil, fmt.Errorf("read CONNECT headers: %w", err)
	}
	code := 0
	if parts := strings.SplitN(status, " ", 3); len(parts) >= 2 {
		code, _ = strconv.Atoi(parts[1])
	}
	if code != 200 {
		conn.Close()
		if code == 401 || code == 403 || code == 404 {
			return nil, nil, nil, errCookieRejected
		}
		return nil, nil, nil, fmt.Errorf("gateway refused tunnel: %s", status)
	}

	p, err := parseTunnelHeaders(hdr)
	if err != nil {
		conn.Close()
		return nil, nil, nil, err
	}
	conn.SetDeadline(time.Time{})
	return conn, br, p, nil
}

func parseTunnelHeaders(h textproto.MIMEHeader) (*tunnelParams, error) {
	p := &tunnelParams{mtu: requestedMTU}
	if a := strings.TrimSpace(h.Get("X-Cstp-Address")); a != "" {
		addr, err := netip.ParseAddr(a)
		if err != nil {
			return nil, fmt.Errorf("bad X-CSTP-Address %q", a)
		}
		p.addr4 = addr
	}
	if a := strings.TrimSpace(h.Get("X-Cstp-Address-Ip6")); a != "" {
		if pr, err := netip.ParsePrefix(a); err == nil {
			p.addr6 = pr.Addr()
		} else if addr, err := netip.ParseAddr(a); err == nil {
			p.addr6 = addr
		}
	}
	if !p.addr4.IsValid() && !p.addr6.IsValid() {
		return nil, errors.New("gateway assigned no tunnel address")
	}
	for _, key := range []string{"X-Cstp-Dns", "X-Cstp-Dns-Ip6"} {
		for _, v := range h.Values(key) {
			for _, s := range strings.Split(v, ",") {
				if addr, err := netip.ParseAddr(strings.TrimSpace(s)); err == nil {
					p.dns = append(p.dns, addr)
				}
			}
		}
	}
	if m, err := strconv.Atoi(strings.TrimSpace(h.Get("X-Cstp-Mtu"))); err == nil && m >= minMTU && m <= 9000 {
		p.mtu = m
	}
	if v, err := strconv.Atoi(strings.TrimSpace(h.Get("X-Cstp-Dpd"))); err == nil && v > 0 {
		p.dpd = time.Duration(v) * time.Second
	}
	if v, err := strconv.Atoi(strings.TrimSpace(h.Get("X-Cstp-Keepalive"))); err == nil && v > 0 {
		p.keepalive = time.Duration(v) * time.Second
	}
	return p, nil
}

func frameHeader(b []byte, typ byte, payloadLen int) {
	b[0], b[1], b[2], b[3] = 'S', 'T', 'F', 0x01
	binary.BigEndian.PutUint16(b[4:6], uint16(payloadLen))
	b[6] = typ
	b[7] = 0
}

func control(typ byte, payload []byte) []byte {
	b := make([]byte, hdrLen+len(payload))
	frameHeader(b, typ, len(payload))
	copy(b[hdrLen:], payload)
	return b
}

// session owns one authenticated AnyConnect session: the current TLS
// connection (replaced on reconnect), the netstack TUN it feeds, and the
// keepalive / dead-peer machinery. The netstack outlives individual TLS
// connections, so in-flight TCP streams through it ride out a roam from
// Wi-Fi to mobile: gVisor retransmits while we reconnect with the same
// cookie, and the gateway hands back the same address.
type session struct {
	cfg     *Config
	base    *url.URL
	tlsConf *tls.Config
	params  *tunnelParams
	dev     tun.Device

	mu     sync.Mutex
	cookie string
	conn   *tls.Conn
	br     *bufio.Reader
	closed bool

	writeMu  sync.Mutex
	lastRx   atomic.Int64
	lastTx   atomic.Int64
	done     chan struct{}
	kick     chan struct{} // wakes a reconnect backoff early (network came back)
	prompter Prompter      // for re-login when the gateway expired the session

	// onFatal is called once if the session ends for a reason other than
	// Close (credentials no longer accepted, server terminated the session,
	// address changed), so the handle can tear the netstack down and make
	// subsequent dials fail fast with the cause.
	onFatal func(error)
}

func newSession(cfg *Config, base *url.URL, tlsConf *tls.Config, cookie string,
	conn *tls.Conn, br *bufio.Reader, p *tunnelParams, dev tun.Device, onFatal func(error)) *session {
	s := &session{
		cfg: cfg, base: base, tlsConf: tlsConf, params: p, dev: dev,
		cookie: cookie, conn: conn, br: br,
		done: make(chan struct{}), kick: make(chan struct{}, 1), onFatal: onFatal,
	}
	now := time.Now().UnixNano()
	s.lastRx.Store(now)
	s.lastTx.Store(now)
	return s
}

func (s *session) start() {
	go s.rxLoop()
	go s.txLoop()
	go s.timerLoop()
}

func (s *session) current() (*tls.Conn, *bufio.Reader) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn, s.br
}

func (s *session) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

// writeFrame writes a complete frame on the current connection. With no
// connection (mid-reconnect) the frame is dropped — TCP inside the
// netstack retransmits. A failed write kills the connection so rxLoop
// notices and reconnects.
func (s *session) writeFrame(frame []byte) error {
	conn, _ := s.current()
	if conn == nil {
		return errors.New("not connected")
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
	_, err := conn.Write(frame)
	if err != nil {
		conn.Close()
		return err
	}
	s.lastTx.Store(time.Now().UnixNano())
	return nil
}

// txLoop moves packets the netstack emits into CSTP data frames. The
// packet is read at offset hdrLen so the header is written in front of it
// without a copy.
func (s *session) txLoop() {
	buf := make([]byte, hdrLen+maxPayload)
	bufs := [][]byte{buf}
	sizes := []int{0}
	for {
		n, err := s.dev.Read(bufs, sizes, hdrLen)
		if err != nil {
			return // device closed
		}
		if n == 0 || sizes[0] == 0 {
			continue
		}
		frameHeader(buf, pktData, sizes[0])
		_ = s.writeFrame(buf[:hdrLen+sizes[0]])
	}
}

// rxLoop reads frames from the current connection and, when it breaks,
// reconnects until it succeeds, the session is closed, or the failure is
// one retrying can't fix.
func (s *session) rxLoop() {
	for {
		conn, br := s.current()
		err := s.readFrames(br)
		conn.Close()
		if s.isClosed() {
			return
		}
		var fatal *fatalErr
		if errors.As(err, &fatal) {
			s.fail(fatal.err)
			return
		}
		log.Printf("haven-oc: tunnel connection lost (%v), reconnecting", err)
		if err := s.reconnect(); err != nil {
			if !s.isClosed() {
				s.fail(err)
			}
			return
		}
	}
}

type fatalErr struct{ err error }

func (f *fatalErr) Error() string { return f.err.Error() }

func (s *session) readFrames(br *bufio.Reader) error {
	var hdr [hdrLen]byte
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			return err
		}
		if hdr[0] != 'S' || hdr[1] != 'T' || hdr[2] != 'F' || hdr[3] != 0x01 {
			return fmt.Errorf("bad CSTP frame magic % x", hdr[:4])
		}
		n := int(binary.BigEndian.Uint16(hdr[4:6]))
		payload := make([]byte, n)
		if _, err := io.ReadFull(br, payload); err != nil {
			return err
		}
		s.lastRx.Store(time.Now().UnixNano())
		switch hdr[6] {
		case pktData:
			if n > 0 {
				_, _ = s.dev.Write([][]byte{payload}, 0)
			}
		case pktDPDReq:
			_ = s.writeFrame(control(pktDPDResp, payload))
		case pktDPDResp, pktKeepalive:
		case pktDisconnect, pktTerminate:
			return &fatalErr{errors.New("gateway ended the VPN session")}
		case pktCompressed:
			// Never negotiated; a gateway that sends it anyway is
			// misbehaving, but dropping one packet beats killing the tunnel.
		}
	}
}

// timerLoop sends keepalives when idle, probes with DPD when the gateway
// goes quiet, and kills a connection that stays silent so rxLoop
// reconnects. Mobile networks drop idle NAT mappings silently; without
// this a dead TCP connection can look alive for many minutes.
func (s *session) timerLoop() {
	dpd := s.params.dpd
	if dpd <= 0 || dpd > 30*time.Second {
		dpd = 30 * time.Second
	}
	keepalive := s.params.keepalive
	if keepalive <= 0 || keepalive > 30*time.Second {
		keepalive = 30 * time.Second
	}
	deadAfter := 3 * dpd
	if deadAfter < 30*time.Second {
		deadAfter = 30 * time.Second
	}
	tick := time.NewTicker(time.Second * 5)
	defer tick.Stop()
	var lastProbe time.Time
	for {
		select {
		case <-s.done:
			return
		case now := <-tick.C:
			sinceRx := now.Sub(time.Unix(0, s.lastRx.Load()))
			sinceTx := now.Sub(time.Unix(0, s.lastTx.Load()))
			switch {
			case sinceRx > deadAfter:
				if conn, _ := s.current(); conn != nil {
					log.Printf("haven-oc: no traffic from gateway for %s, dropping connection", sinceRx.Round(time.Second))
					conn.Close()
				}
				s.lastRx.Store(now.UnixNano()) // don't re-fire while reconnecting
			case sinceRx > dpd && now.Sub(lastProbe) > dpd:
				lastProbe = now
				_ = s.writeFrame(control(pktDPDReq, nil))
			case sinceTx > keepalive:
				_ = s.writeFrame(control(pktKeepalive, nil))
			}
		}
	}
}

// reconnect re-establishes the TLS connection with exponential backoff
// capped at 30s, indefinitely — a phone can be offline for a long time
// and the tunnel should come back on its own. If the gateway has expired
// the cookie, it re-authenticates (works unattended with password + TOTP
// or a static second factor). Gives up only on errors retrying can't fix.
func (s *session) reconnect() error {
	s.mu.Lock()
	s.conn, s.br = nil, nil
	s.mu.Unlock()

	backoff := time.Second
	for attempt := 1; ; attempt++ {
		select {
		case <-s.done:
			return errors.New("closed")
		case <-s.kick:
			backoff = time.Second // network changed: retry promptly from now on
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
		}

		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		s.mu.Lock()
		cookie := s.cookie
		s.mu.Unlock()
		conn, br, p, err := connectCSTP(ctx, s.cfg, s.base, s.tlsConf, cookie)
		cancel()
		if errors.Is(err, errCookieRejected) {
			log.Printf("haven-oc: session expired on gateway, re-authenticating")
			cookie, err = authenticate(s.cfg, s.base, s.tlsConf, s.prompter)
			if err == nil {
				ctx, cancel = context.WithTimeout(context.Background(), 45*time.Second)
				conn, br, p, err = connectCSTP(ctx, s.cfg, s.base, s.tlsConf, cookie)
				cancel()
			}
		}
		var rej *errAuthRejected
		if errors.As(err, &rej) {
			return err
		}
		if err != nil {
			log.Printf("haven-oc: reconnect attempt %d failed: %v", attempt, err)
			continue
		}
		if !p.sameAddresses(s.params) {
			conn.Close()
			return fmt.Errorf("gateway assigned a different address on reconnect (%v, was %v); reconnect the profile",
				p.addresses(), s.params.addresses())
		}

		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			conn.Close()
			return errors.New("closed")
		}
		s.cookie, s.conn, s.br = cookie, conn, br
		s.mu.Unlock()
		now := time.Now().UnixNano()
		s.lastRx.Store(now)
		s.lastTx.Store(now)
		log.Printf("haven-oc: reconnected after %d attempt(s)", attempt)
		return nil
	}
}

func (s *session) fail(err error) {
	log.Printf("haven-oc: tunnel down: %v", err)
	if s.onFatal != nil {
		s.onFatal(err)
	}
}

// close ends this connection. With logout it also ends the session on the
// gateway (CSTP disconnect + logout request), so the cookie dies and the next
// start needs a full sign-in. Without, it just drops the TLS connection — no
// disconnect frame, the way openconnect pauses — so the gateway keeps the
// session for its idle timeout and the cookie can resume it without asking
// for a password or SMS code again. Returns the cookie. Idempotent.
func (s *session) close(logoutToo bool) string {
	s.mu.Lock()
	if s.closed {
		cookie := s.cookie
		s.mu.Unlock()
		return cookie
	}
	s.closed = true
	conn := s.conn
	cookie := s.cookie
	s.mu.Unlock()
	close(s.done)

	if conn != nil {
		if logoutToo {
			s.writeMu.Lock()
			conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
			_, _ = conn.Write(control(pktDisconnect, []byte{0xb0}))
			s.writeMu.Unlock()
		}
		conn.Close()
	}
	if logoutToo {
		go logout(s.cfg, s.base, s.tlsConf, cookie)
	}
	return cookie
}

// currentCookie is the session's cookie as of now (it changes on re-login).
func (s *session) currentCookie() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cookie
}

// revive is called when Android reports a network change or Haven returns to
// the foreground. The Cisco client reacts to network changes at once; waiting
// for DPD to notice a dead socket instead takes 30-90 s. A DPD probe costs
// one small frame: if the gateway answers within probeWait the connection is
// fine and nothing happens; otherwise it is dropped and reconnected now. A
// pending reconnect backoff is cut short either way.
const probeWait = 3 * time.Second

func (s *session) revive() {
	select {
	case s.kick <- struct{}{}:
	default:
	}
	conn, _ := s.current()
	if conn == nil {
		return // reconnecting; the kick above wakes it
	}
	sent := time.Now()
	if err := s.writeFrame(control(pktDPDReq, nil)); err != nil {
		return // write failure already closed it; rxLoop reconnects
	}
	go func() {
		select {
		case <-s.done:
			return
		case <-time.After(probeWait):
		}
		if time.Unix(0, s.lastRx.Load()).Before(sent) {
			if cur, _ := s.current(); cur == conn {
				log.Printf("haven-oc: no answer to probe after network change, reconnecting")
				conn.Close()
			}
		}
	}()
}
