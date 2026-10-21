package ocbridge

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/textproto"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// --- pure helpers -----------------------------------------------------------

func TestTOTP_RFC6238Vectors(t *testing.T) {
	// RFC 6238 Appendix B, SHA-1 seed "12345678901234567890", 8 digits.
	tt, err := newTOTP("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8")
	if err != nil {
		t.Fatal(err)
	}
	for ts, want := range map[int64]string{
		59: "94287082", 1111111109: "07081804", 1234567890: "89005924", 2000000000: "69279037",
	} {
		if got := tt.codeAt(time.Unix(ts, 0)); got != want {
			t.Errorf("t=%d: got %s want %s", ts, got, want)
		}
	}
	// Bare secret, lower case, spaces: 6-digit default is the low 6 digits.
	t6, err := newTOTP("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")
	if err != nil {
		t.Fatal(err)
	}
	if got := t6.codeAt(time.Unix(59, 0)); got != "287082" {
		t.Errorf("6-digit: got %s", got)
	}
	if _, err := newTOTP("not base32 !!"); err == nil {
		t.Error("expected error for invalid secret")
	}
}

func TestParsePin(t *testing.T) {
	good := []string{
		"pin-sha256:" + base64.StdEncoding.EncodeToString(make([]byte, 32)),
		"sha256:" + strings.Repeat("ab", 32),
		strings.Repeat("AB:", 31) + "AB",
		"sha1:" + strings.Repeat("0f", 20),
	}
	for _, s := range good {
		if _, err := parsePin(s); err != nil {
			t.Errorf("%q: %v", s, err)
		}
	}
	for _, s := range []string{"pin-sha256:short", "sha256:abc", "hello"} {
		if _, err := parsePin(s); err == nil {
			t.Errorf("%q: expected error", s)
		}
	}
}

func TestNormalizeServer(t *testing.T) {
	cases := map[string]string{
		"vpn.example.com":                 "https://vpn.example.com/",
		"vpn.example.com:8443":            "https://vpn.example.com:8443/",
		"https://vpn.example.com/eng?x=1": "https://vpn.example.com/eng",
	}
	for in, want := range cases {
		u, err := normalizeServer(in)
		if err != nil || u.String() != want {
			t.Errorf("%q: got %v %v want %s", in, u, err, want)
		}
	}
	if _, err := normalizeServer("http://vpn.example.com"); err == nil {
		t.Error("plain http must be refused")
	}
}

func TestParseTunnelHeaders(t *testing.T) {
	h := textproto.MIMEHeader{}
	h.Set("X-CSTP-Address", "10.10.0.5")
	h.Set("X-CSTP-Address-IP6", "fd00::5/64")
	h.Add("X-CSTP-DNS", "10.10.0.1")
	h.Add("X-CSTP-DNS", "fd00::1")
	h.Set("X-CSTP-MTU", "1300")
	h.Set("X-CSTP-DPD", "20")
	p, err := parseTunnelHeaders(h)
	if err != nil {
		t.Fatal(err)
	}
	if p.addr4.String() != "10.10.0.5" || p.addr6.String() != "fd00::5" || len(p.dns) != 2 ||
		p.mtu != 1300 || p.dpd != 20*time.Second {
		t.Fatalf("unexpected params %+v", p)
	}
	if _, err := parseTunnelHeaders(textproto.MIMEHeader{}); err == nil {
		t.Error("expected error without an address")
	}
}

func TestReplyXMLEscapesAndDropsBadNames(t *testing.T) {
	x := replyXML("<opaque is-for=\"sg\">x</opaque>",
		[][2]string{{"username", "a<b&c"}, {"bad name><evil", "x"}}, "Eng")
	if !strings.Contains(x, "<username>a&lt;b&amp;c</username>") {
		t.Error("value not escaped:", x)
	}
	if strings.Contains(x, "evil") {
		t.Error("hostile field name echoed:", x)
	}
	if !strings.Contains(x, "<group-select>Eng</group-select>") || !strings.Contains(x, `<opaque is-for="sg">x</opaque>`) {
		t.Error("missing group-select or opaque:", x)
	}
}

// --- fake gateway -----------------------------------------------------------

const (
	gwAddr     = "10.77.0.1"
	clientAddr = "10.77.0.2"
	otpSecret  = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
)

// fakeGateway imitates an ASA: an init POST gets a form with a group list;
// selecting group "Eng" yields a username/password form; correct
// credentials yield an OTP challenge; a valid TOTP completes. CONNECT then
// bridges CSTP frames to a server-side gVisor netstack with a TCP echo
// service, which is the "corporate network" the client reaches.
type fakeGateway struct {
	t   *testing.T
	srv *httptest.Server

	sdev tun.Device
	snet *netstack.Net

	mu          sync.Mutex
	tunnelConns []net.Conn
	connects    atomic.Int32
	logouts     atomic.Int32
	authRounds  []string
	rejectNext  atomic.Bool // force the next CONNECT to 401 (expired cookie)
	revoked     sync.Map    // cookies ended by a logout request
	silenced    atomic.Value // net.Conn the gateway stops answering on (dead NAT)
	wmu         sync.Mutex  // guards cur and serialises frame writes
	cur         net.Conn    // tunnel connection packets are forwarded to
	issued      atomic.Int32

	// asa replays the sign-in of a real Cisco ASA (reported in #724
	// follow-up): a POST whose Content-Type isn't XML gets 404, the first
	// init gets a bare <client-cert-request>, and only an init carrying
	// <client-cert-fail/> gets the login form.
	asa atomic.Bool
	// asaCertRequired makes that gateway insist on a certificate even after
	// <client-cert-fail/>, as one that truly requires one would.
	asaCertRequired atomic.Bool
	asaPaths        []string // request paths seen in ASA mode, for the 404 message test
}

func newFakeGateway(t *testing.T) *fakeGateway {
	g := &fakeGateway{t: t}
	var err error
	g.sdev, g.snet, err = netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(gwAddr)}, nil, 1300)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := g.snet.ListenTCPAddrPort(netip.MustParseAddrPort(gwAddr + ":7"))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	go g.pumpDown()
	g.srv = httptest.NewTLSServer(http.HandlerFunc(g.handle))
	t.Cleanup(func() {
		g.srv.Close()
		g.sdev.Close()
	})
	return g
}

func (g *fakeGateway) pin() string {
	h := sha256.Sum256(g.srv.Certificate().RawSubjectPublicKeyInfo)
	return "pin-sha256:" + base64.StdEncoding.EncodeToString(h[:])
}

func (g *fakeGateway) handle(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodConnect && r.RequestURI == "/CSCOSSLC/tunnel":
		g.handleConnect(w, r)
		return
	case r.URL.Path == "/+webvpn+/webvpn_logout.html":
		g.logouts.Add(1)
		if c, err := r.Cookie("webvpn"); err == nil {
			g.revoked.Store(c.Value, true)
		}
		return
	}
	body, _ := io.ReadAll(r.Body)
	s := string(body)
	g.mu.Lock()
	g.authRounds = append(g.authRounds, s)
	g.mu.Unlock()
	if g.asa.Load() {
		g.handleASA(w, r, s)
		return
	}
	w.Header().Set("Content-Type", "text/xml")

	opaque := `<opaque is-for="sg"><tunnel-group>X</tunnel-group></opaque>`
	switch {
	case strings.Contains(s, `type="init"`):
		fmt.Fprint(w, authRequest(opaque, "", groupForm("Default")))
	case strings.Contains(s, "<group-select>Eng</group-select>") && !strings.Contains(s, "<username>"):
		fmt.Fprint(w, authRequest(opaque, "", groupForm("Eng")))
	case strings.Contains(s, "<username>alice</username>") && strings.Contains(s, "<password>s3cret</password>"):
		if !strings.Contains(s, opaque) {
			fmt.Fprint(w, authRequest(opaque, "opaque not echoed", ""))
			return
		}
		fmt.Fprint(w, authRequest(opaque, "", `<form method="post" action="/">
<input type="password" name="secondary_password" label="Passcode:"></input></form>`))
	case strings.Contains(s, "<secondary_password>"):
		tt, _ := newTOTP(otpSecret)
		if !strings.Contains(s, "<secondary_password>"+tt.codeAt(time.Now())+"</secondary_password>") {
			fmt.Fprint(w, authRequest(opaque, "Invalid passcode", ""))
			return
		}
		n := g.issued.Add(1)
		fmt.Fprintf(w, `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="complete" aggregate-auth-version="2">
<session-id>1</session-id><session-token>COOKIE%d</session-token>
<auth id="success"><message id="0" param1="" param2=""></message></auth>
</config-auth>`, n)
	default:
		fmt.Fprint(w, authRequest(opaque, "Login failed.", groupForm("Eng")))
	}
}

// asaCode is the SMS code the ASA-mode gateway "sent".
const asaCode = "481516"

func (g *fakeGateway) handleASA(w http.ResponseWriter, r *http.Request, s string) {
	g.mu.Lock()
	g.asaPaths = append(g.asaPaths, r.URL.Path)
	g.mu.Unlock()
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/xml") {
		// What the reporter's ASA does with a form-encoded POST to "/".
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	w.Header().Set("X-Aggregate-Auth", "1")
	const head = `<?xml version="1.0" encoding="UTF-8"?>` + "\n" +
		`<config-auth client="vpn" type="auth-request" aggregate-auth-version="2">` + "\n"
	opaque := "<opaque is-for=\"sg\">\n<tunnel-group>DefaultWEBVPNGroup</tunnel-group>\n" +
		"<auth-method>single-sign-on-v2</auth-method>\n<config-hash>1781561857145</config-hash>\n</opaque>"
	switch {
	case strings.Contains(s, `type="init"`) &&
		(!strings.Contains(s, "<client-cert-fail") || g.asaCertRequired.Load()):
		fmt.Fprint(w, head+"<client-cert-request></client-cert-request>\n</config-auth>")
	case strings.Contains(s, `type="init"`):
		fmt.Fprint(w, head+opaque+`
<auth id="main">
<title>Login</title>
<message>Please enter your username and password.</message>
<banner></banner>
<form>
<input type="text" name="username" label="Username:"></input>
<input type="password" name="password" label="Password:"></input>
</form>
</auth>
</config-auth>`)
	case strings.Contains(s, "<username>alice</username>") && strings.Contains(s, "<password>s3cret</password>"):
		if !strings.Contains(s, "<tunnel-group>DefaultWEBVPNGroup</tunnel-group>") {
			fmt.Fprint(w, head+`<auth id="main"><error id="1">opaque not echoed</error></auth></config-auth>`)
			return
		}
		fmt.Fprint(w, head+opaque+`
<auth id="challenge">
<title>Login</title>
<message>Enter the code sent to your phone by SMS.</message>
<form>
<input type="password" name="answer" label="Response:"></input>
<input type="submit" name="Continue" label="continue"></input>
</form>
</auth>
</config-auth>`)
	case strings.Contains(s, "<answer>"+asaCode+"</answer>"):
		n := g.issued.Add(1)
		fmt.Fprintf(w, `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="complete" aggregate-auth-version="2">
<session-token>COOKIE%d</session-token>
<auth id="success"><message>Success</message></auth>
</config-auth>`, n)
	default:
		fmt.Fprint(w, head+`<auth id="main"><error id="2">Login failed.</error></auth></config-auth>`)
	}
}

func groupForm(selected string) string {
	sel := func(v string) string {
		if v == selected {
			return ` selected="true"`
		}
		return ""
	}
	return fmt.Sprintf(`<form method="post" action="/">
<input type="text" name="username" label="Username:"></input>
<input type="password" name="password" label="Password:"></input>
<select name="group_list" label="GROUP:">
<option value="Default"%s>Default</option><option value="Eng"%s>Engineering</option>
</select></form>`, sel("Default"), sel("Eng"))
}

func authRequest(opaque, errText, form string) string {
	e := ""
	if errText != "" {
		e = `<error id="88" param1="" param2="">` + errText + `</error>`
	}
	return `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="auth-request" aggregate-auth-version="2">` + opaque +
		`<auth id="main"><title>Login</title>` + e + form + `</auth></config-auth>`
}

func (g *fakeGateway) handleConnect(w http.ResponseWriter, r *http.Request) {
	g.connects.Add(1)
	cookie := ""
	if c, err := r.Cookie("webvpn"); err == nil {
		cookie = c.Value
	}
	hj, _ := w.(http.Hijacker)
	conn, rw, err := hj.Hijack()
	if err != nil {
		return
	}
	_, revoked := g.revoked.Load(cookie)
	if g.rejectNext.Swap(false) || revoked || !strings.HasPrefix(cookie, "COOKIE") {
		fmt.Fprint(conn, "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
		conn.Close()
		return
	}
	fmt.Fprintf(conn, "HTTP/1.1 200 CONNECTED\r\nX-CSTP-Version: 1\r\nX-CSTP-Address: %s\r\n"+
		"X-CSTP-Netmask: 255.255.255.0\r\nX-CSTP-DNS: %s\r\nX-CSTP-MTU: 1300\r\nX-CSTP-DPD: 30\r\n"+
		"X-CSTP-Keepalive: 20\r\n\r\n", clientAddr, gwAddr)
	g.mu.Lock()
	g.tunnelConns = append(g.tunnelConns, conn)
	g.mu.Unlock()

	write := func(f []byte) error {
		g.wmu.Lock()
		defer g.wmu.Unlock()
		_, err := conn.Write(f)
		return err
	}
	g.wmu.Lock()
	g.cur = conn
	g.wmu.Unlock()
	// client → server netstack
	br := rw.Reader
	var hdr [hdrLen]byte
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			break
		}
		n := int(binary.BigEndian.Uint16(hdr[4:6]))
		p := make([]byte, n)
		if _, err := io.ReadFull(br, p); err != nil {
			break
		}
		if g.silenced.Load() == conn {
			continue // a dead path: nothing gets through, nothing comes back
		}
		switch hdr[6] {
		case pktData:
			g.sdev.Write([][]byte{p}, 0)
		case pktDPDReq:
			write(control(pktDPDResp, p))
		case pktDisconnect:
			conn.Close()
		}
	}
	g.wmu.Lock()
	if g.cur == conn {
		g.cur = nil
	}
	g.wmu.Unlock()
	conn.Close()
}

// pumpDown is the gateway's single reader of its netstack: it must keep
// draining the device for the life of the test (gVisor blocks on an
// undrained TUN, which would deadlock Close), forwarding to whichever
// tunnel connection is current and dropping packets in between.
func (g *fakeGateway) pumpDown() {
	buf := make([]byte, hdrLen+maxPayload)
	sizes := []int{0}
	for {
		n, err := g.sdev.Read([][]byte{buf}, sizes, hdrLen)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		frameHeader(buf, pktData, sizes[0])
		g.wmu.Lock()
		if g.cur != nil && g.silenced.Load() != g.cur {
			g.cur.Write(buf[:hdrLen+sizes[0]])
		}
		g.wmu.Unlock()
	}
}

// silenceCurrent makes the live tunnel connection a black hole without
// closing it: what a phone sees after leaving Wi-Fi with a socket that
// will never error on its own.
func (g *fakeGateway) silenceCurrent() {
	g.wmu.Lock()
	defer g.wmu.Unlock()
	if g.cur != nil {
		g.silenced.Store(g.cur)
	}
}

func (g *fakeGateway) rounds() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return len(g.authRounds)
}

func (g *fakeGateway) dropTunnels() {
	g.mu.Lock()
	defer g.mu.Unlock()
	for _, c := range g.tunnelConns {
		c.Close()
	}
	g.tunnelConns = nil
}

func (g *fakeGateway) config(mod func(*Config)) string {
	c := Config{
		Server:     g.srv.URL + "/eng",
		Username:   "alice",
		Password:   "s3cret",
		AuthGroup:  "Eng",
		OTPSecret:  otpSecret,
		ServerCert: g.pin(),
	}
	if mod != nil {
		mod(&c)
	}
	b, _ := json.Marshal(c)
	return string(b)
}

func echo(t *testing.T, c *Conn, msg string) {
	t.Helper()
	if err := c.Write([]byte(msg)); err != nil {
		t.Fatalf("write: %v", err)
	}
	var got bytes.Buffer
	for got.Len() < len(msg) {
		b, err := c.Read(len(msg) - got.Len())
		if err != nil {
			t.Fatalf("read after %q: %v", got.String(), err)
		}
		got.Write(b)
	}
	if got.String() != msg {
		t.Fatalf("echo: got %q want %q", got.String(), msg)
	}
}

// --- end-to-end against the fake gateway -------------------------------------

func TestEndToEnd_AuthTunnelEchoAndLogout(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	if h.BindAddr() != clientAddr {
		t.Errorf("BindAddr = %q", h.BindAddr())
	}
	// init, group switch, credentials, OTP
	if len(g.authRounds) != 4 {
		t.Errorf("expected 4 auth rounds, got %d", len(g.authRounds))
	}

	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "hello through anyconnect")
	big := strings.Repeat("0123456789abcdef", 64*1024/16) // > MTU, many segments
	echo(t, c, big)
	if c.RemoteAddrHost() != gwAddr {
		t.Errorf("RemoteAddrHost = %q", c.RemoteAddrHost())
	}
	c.Close()

	// SOCKS5 front door
	port, err := h.StartSocksListener()
	if err != nil || port == 0 {
		t.Fatalf("socks: %d %v", port, err)
	}

	h.Logout()
	h.Logout() // idempotent
	h.Close()  // and harmless after Logout
	if _, err := h.Dial(gwAddr, 7, 1000); err == nil {
		t.Error("dial after close should fail")
	}
	deadline := time.Now().Add(3 * time.Second)
	for g.logouts.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if g.logouts.Load() == 0 {
		t.Error("expected a logout request on Logout")
	}
}

func TestEndToEnd_SurvivesConnectionDrop(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "before")

	// Network change: the TLS connection dies under an open TCP stream.
	g.dropTunnels()
	echo(t, c, "after reconnect, same stream") // gVisor retransmits over the new connection
	if g.connects.Load() < 2 {
		t.Errorf("expected a reconnect, connects=%d", g.connects.Load())
	}

	// Expired cookie: gateway 401s the reconnect; client re-auths with a fresh TOTP.
	issuedBefore := g.issued.Load()
	g.rejectNext.Store(true)
	g.dropTunnels()
	echo(t, c, "after re-auth")
	if g.issued.Load() <= issuedBefore {
		t.Error("expected re-authentication after cookie rejection")
	}
	c.Close()
}

func TestAuthFailures(t *testing.T) {
	g := newFakeGateway(t)
	cases := []struct {
		name string
		mod  func(*Config)
		want string
	}{
		{"bad password", func(c *Config) { c.Password = "nope" }, "Login failed"},
		{"unknown group", func(c *Config) { c.AuthGroup = "Sales" }, "not offered"},
		{"missing second factor", func(c *Config) { c.OTPSecret = "" }, "second factor"},
		{"wrong static second factor", func(c *Config) { c.OTPSecret = ""; c.SecondaryPassword = "push" }, "Invalid passcode"},
		{"wrong pin", func(c *Config) { c.ServerCert = "sha256:" + strings.Repeat("00", 32) }, "does not match"},
		{"no pin, self-signed", func(c *Config) { c.ServerCert = "" }, "pin-sha256:"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := StartTunnel(g.config(tc.mod))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("got %v, want error containing %q", err, tc.want)
			}
		})
	}
}

func TestStartTunnelRejectsBadConfig(t *testing.T) {
	for _, s := range []string{"", "{}", `{"server":"x"}`, `{"server":"x","username":"u","otpSecret":"!!"}`} {
		if _, err := StartTunnel(s); err == nil {
			t.Errorf("%q: expected error", s)
		}
	}
}

// --- session resume, prompts, revive -----------------------------------------

func TestCloseKeepsSessionResumable(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	before := g.rounds()
	h.Close()
	if g.logouts.Load() != 0 {
		t.Fatal("Close must not log out")
	}
	if !HasResumableSession(g.config(nil)) {
		t.Fatal("expected a resumable session after Close")
	}

	h2, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h2.Close()
	if g.rounds() != before {
		t.Fatalf("resume must not sign in again (auth rounds %d -> %d)", before, g.rounds())
	}
	c, err := h2.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "resumed without a new code")
	c.Close()
}

func TestResumeFallsBackToSignInWhenExpired(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	h.Close()
	issued := g.issued.Load()
	g.rejectNext.Store(true) // gateway idle timeout passed
	h2, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h2.Close()
	if g.issued.Load() != issued+1 {
		t.Fatal("expected a fresh sign-in after the cached session was rejected")
	}
}

func TestResumeWhileOfflineDoesNotSignIn(t *testing.T) {
	g := newFakeGateway(t)
	cfg := g.config(nil)
	h, err := StartTunnel(cfg)
	if err != nil {
		t.Fatal(err)
	}
	h.Close()
	g.srv.Close() // gateway unreachable
	before := g.rounds()
	asked := false
	_, err = StartTunnelWithPrompter(cfg, promptFunc(func(_, _, _ string, _ bool) string {
		asked = true
		return ""
	}))
	if err == nil || !strings.Contains(err.Error(), "open tunnel") {
		t.Fatalf("want an open-tunnel error, got %v", err)
	}
	if asked || g.rounds() != before {
		t.Fatal("must not start a sign-in (and burn an SMS code) while the gateway is unreachable")
	}
	if !HasResumableSession(cfg) {
		t.Fatal("an unreachable gateway must not discard the resumable session")
	}
}

func TestLogoutAndEndSessionForgetTheSession(t *testing.T) {
	g := newFakeGateway(t)
	cfg := g.config(nil)
	h, err := StartTunnel(cfg)
	if err != nil {
		t.Fatal(err)
	}
	h.Logout()
	if HasResumableSession(cfg) {
		t.Fatal("Logout must forget the session")
	}

	h, err = StartTunnel(cfg)
	if err != nil {
		t.Fatal(err)
	}
	h.Close()
	logouts := g.logouts.Load()
	EndSession(cfg) // e.g. "Sign out" on an idle tunnel
	if HasResumableSession(cfg) || g.logouts.Load() != logouts+1 {
		t.Fatal("EndSession must log the cached session out and forget it")
	}
}

type promptFunc func(title, message, label string, secret bool) string

func (f promptFunc) Prompt(title, message, label string, secret bool) string {
	return f(title, message, label, secret)
}

func TestPrompterAnswersSMSCode(t *testing.T) {
	g := newFakeGateway(t)
	var gotLabel string
	var gotSecret bool
	h, err := StartTunnelWithPrompter(
		g.config(func(c *Config) { c.OTPSecret = "" }),
		promptFunc(func(title, message, label string, secret bool) string {
			gotLabel, gotSecret = label, secret
			tt, _ := newTOTP(otpSecret) // stands in for the code the user read off the SMS
			return " " + tt.codeAt(time.Now()) + " "
		}),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Logout()
	if gotLabel != "Passcode" || gotSecret {
		t.Errorf("prompt label=%q secret=%v", gotLabel, gotSecret)
	}
}

func TestPrompterAsksForUnstoredPassword(t *testing.T) {
	g := newFakeGateway(t)
	var asked []string
	h, err := StartTunnelWithPrompter(
		g.config(func(c *Config) { c.Password = "" }),
		promptFunc(func(_, _, label string, secret bool) string {
			asked = append(asked, label)
			if secret {
				return "s3cret"
			}
			return ""
		}),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Logout()
	if len(asked) != 1 || asked[0] != "Password" {
		t.Errorf("asked %v, want only the password (OTP comes from the secret)", asked)
	}
}

func TestPrompterCancelStopsSignIn(t *testing.T) {
	g := newFakeGateway(t)
	_, err := StartTunnelWithPrompter(
		g.config(func(c *Config) { c.OTPSecret = "" }),
		promptFunc(func(_, _, _ string, _ bool) string { return "" }),
	)
	if err == nil || !strings.Contains(err.Error(), "cancelled") {
		t.Fatalf("want cancellation, got %v", err)
	}
}

func TestReviveReplacesSilentlyDeadConnection(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "before network change")

	g.silenceCurrent() // left Wi-Fi: the socket neither errors nor delivers
	connects := g.connects.Load()
	start := time.Now()
	h.Revive() // Android reported a network change
	deadline := time.Now().Add(10 * time.Second)
	for g.connects.Load() == connects && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if g.connects.Load() == connects {
		t.Fatal("Revive did not replace the dead connection")
	}
	if took := time.Since(start); took > 6*time.Second {
		t.Errorf("reconnect took %s; DPD alone would take 30 s or more", took)
	}
	echo(t, c, "same stream after the network change")
	c.Close()
}

func TestReviveLeavesHealthyConnectionAlone(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	connects := g.connects.Load()
	h.Revive() // e.g. Haven returning to the foreground
	time.Sleep(probeWait + time.Second)
	if g.connects.Load() != connects {
		t.Fatal("Revive must not reconnect a connection that answers the probe")
	}
}

// --- the reporter's Cisco ASA ------------------------------------------------

func asaConfig(g *fakeGateway) string {
	return g.config(func(c *Config) {
		c.Server = strings.TrimPrefix(g.srv.URL, "https://") // bare host:port, as typed
		c.AuthGroup = ""
		c.OTPSecret = ""
	})
}

func TestASA_XMLContentTypeAndClientCertRetry(t *testing.T) {
	g := newFakeGateway(t)
	g.asa.Store(true)
	var prompts []string
	h, err := StartTunnelWithPrompter(asaConfig(g), promptFunc(func(title, message, label string, secret bool) string {
		prompts = append(prompts, label+"|"+message)
		return asaCode // the user types the SMS code
	}))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Logout()
	// init, init + client-cert-fail, credentials, SMS answer
	if n := g.rounds(); n != 4 {
		t.Errorf("auth rounds = %d, want 4", n)
	}
	if len(prompts) != 1 || prompts[0] != "Response|Enter the code sent to your phone by SMS." {
		t.Errorf("prompts = %q", prompts)
	}
	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "through the ASA")
	c.Close()
}

func TestASA_CertificateReallyRequired(t *testing.T) {
	g := newFakeGateway(t)
	g.asa.Store(true)
	g.asaCertRequired.Store(true)
	_, err := StartTunnel(asaConfig(g))
	if err == nil || !strings.Contains(err.Error(), "client certificate") {
		t.Fatalf("want a client-certificate error, got %v", err)
	}
	if n := g.rounds(); n != 2 {
		t.Errorf("should retry exactly once without a certificate, rounds = %d", n)
	}
}

func Test404NamesTheAddressAndHints(t *testing.T) {
	srv := httptest.NewTLSServer(http.NotFoundHandler())
	defer srv.Close()
	h := sha256.Sum256(srv.Certificate().RawSubjectPublicKeyInfo)
	cfg, _ := json.Marshal(Config{
		Server:     strings.TrimPrefix(srv.URL, "https://") + "/engineering",
		Username:   "alice",
		Password:   "pw",
		ServerCert: "pin-sha256:" + base64.StdEncoding.EncodeToString(h[:]),
	})
	_, err := StartTunnel(string(cfg))
	if err == nil {
		t.Fatal("expected an error")
	}
	msg := err.Error()
	for _, want := range []string{"HTTP 404", "/engineering", "Server"} {
		if !strings.Contains(msg, want) {
			t.Errorf("error %q should mention %q", msg, want)
		}
	}
}

// TestLive runs against a real gateway when OCBRIDGE_LIVE_CONFIG holds a
// config JSON and OCBRIDGE_LIVE_ECHO a host:port TCP echo service reachable
// inside the VPN. Skipped otherwise (CI has no gateway).
func TestLive(t *testing.T) {
	cfg, target := os.Getenv("OCBRIDGE_LIVE_CONFIG"), os.Getenv("OCBRIDGE_LIVE_ECHO")
	if cfg == "" || target == "" {
		t.Skip("OCBRIDGE_LIVE_CONFIG / OCBRIDGE_LIVE_ECHO not set")
	}
	h, err := StartTunnel(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	t.Logf("tunnel up, assigned %s", h.BindAddr())
	host, portStr, _ := net.SplitHostPort(target)
	port, _ := strconv.Atoi(portStr)
	c, err := h.Dial(host, port, 15_000)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	echo(t, c, "hello from haven over a real gateway")
	echo(t, c, strings.Repeat("x", 200_000))
}
