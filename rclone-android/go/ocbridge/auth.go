package ocbridge

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// errAuthRejected marks a failure that retrying won't fix (bad password,
// wrong group, unsupported auth method). The reconnect loop gives up on it
// instead of hammering the gateway into locking the account.
type errAuthRejected struct{ msg string }

func (e *errAuthRejected) Error() string { return e.msg }

func rejected(format string, a ...any) error {
	return &errAuthRejected{fmt.Sprintf(format, a...)}
}

// --- XML schema (the subset of Cisco's aggregate-auth we act on) ----------

type xmlConfigAuth struct {
	XMLName           xml.Name  `xml:"config-auth"`
	Type              string    `xml:"type,attr"`
	SessionToken      string    `xml:"session-token"`
	ClientCertRequest *struct{} `xml:"client-cert-request"`
	Auth              xmlAuth   `xml:"auth"`
}

type xmlAuth struct {
	ID       string        `xml:"id,attr"`
	Title    string        `xml:"title"`
	Message  string        `xml:"message"`
	Error    *xmlAuthError `xml:"error"`
	Form     *xmlForm      `xml:"form"`
	SSOLogin string        `xml:"sso-v2-login"`
}

type xmlAuthError struct {
	ID     string `xml:"id,attr"`
	Param1 string `xml:"param1,attr"`
	Param2 string `xml:"param2,attr"`
	Text   string `xml:",chardata"`
}

func (e *xmlAuthError) message() string {
	msg := strings.TrimSpace(e.Text)
	msg = strings.Replace(msg, "%s", e.Param1, 1)
	msg = strings.Replace(msg, "%s", e.Param2, 1)
	return msg
}

type xmlForm struct {
	Action  string      `xml:"action,attr"`
	Inputs  []xmlInput  `xml:"input"`
	Selects []xmlSelect `xml:"select"`
}

type xmlInput struct {
	Type  string `xml:"type,attr"`
	Name  string `xml:"name,attr"`
	Label string `xml:"label,attr"`
	Value string `xml:"value,attr"`
}

type xmlSelect struct {
	Name    string      `xml:"name,attr"`
	Label   string      `xml:"label,attr"`
	Options []xmlOption `xml:"option"`
}

type xmlOption struct {
	Value    string `xml:"value,attr"`
	Selected string `xml:"selected,attr"`
	Text     string `xml:",chardata"`
}

func (o xmlOption) value() string {
	if o.Value != "" {
		return o.Value
	}
	return strings.TrimSpace(o.Text)
}

// The <opaque> block carries server state between rounds and must be echoed
// back byte-for-byte, so it is lifted from the raw body rather than
// round-tripped through encoding/xml.
var opaqueRE = regexp.MustCompile(`(?s)<opaque\b[^>]*>.*?</opaque>`)

const groupListField = "group_list"

// --- request plumbing -------------------------------------------------------

type authClient struct {
	cfg      *Config
	base     *url.URL
	client   *http.Client
	prompter Prompter // nil: no interactive answers (unit tests, MCP-created tunnels)
}

func newAuthClient(cfg *Config, base *url.URL, tlsConf *tls.Config) *authClient {
	jar, _ := cookiejar.New(nil)
	tr := &http.Transport{
		DialContext:         (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSClientConfig:     tlsConf,
		TLSHandshakeTimeout: 15 * time.Second,
		// The gateways speak HTTP/1.1 only; never negotiate h2.
		TLSNextProto: map[string]func(string, *tls.Conn) http.RoundTripper{},
		Proxy:        nil,
	}
	return &authClient{
		cfg:  cfg,
		base: base,
		client: &http.Client{
			Transport: tr,
			Jar:       jar,
			Timeout:   30 * time.Second,
			// Redirects are followed by hand: Go would downgrade the POST to
			// a GET, but the gateway expects the same XML body re-POSTed.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

func setCommonHeaders(h http.Header, ua string) {
	h.Set("User-Agent", ua)
	h.Set("X-Transcend-Version", "1")
	h.Set("X-Aggregate-Auth", "1")
	h.Set("X-AnyConnect-Platform", "linux-64")
	h.Set("X-Support-HTTP-Auth", "false")
	h.Set("Accept", "*/*")
	h.Set("Accept-Encoding", "identity")
}

// post sends an XML body, following up to five redirects by re-POSTing.
// Returns the final URL so relative form actions resolve against it.
func (a *authClient) post(ctx context.Context, target *url.URL, body string) (*url.URL, []byte, error) {
	for hop := 0; hop < 5; hop++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, target.String(), strings.NewReader(body))
		if err != nil {
			return nil, nil, err
		}
		setCommonHeaders(req.Header, a.cfg.UserAgent)
		// The body is XML, and openconnect labels it so. ocserv doesn't
		// care, but a Cisco ASA treats a form-encoded POST to "/" as a
		// request for a web-portal page and answers 404.
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		resp, err := a.client.Do(req)
		if err != nil {
			return nil, nil, err
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		if err != nil {
			return nil, nil, err
		}
		if resp.StatusCode >= 300 && resp.StatusCode < 400 {
			loc, err := resp.Location()
			if err != nil {
				return nil, nil, fmt.Errorf("redirect %d without Location", resp.StatusCode)
			}
			if loc.Scheme != "https" {
				return nil, nil, fmt.Errorf("refusing redirect to non-https %s", loc)
			}
			target = loc
			continue
		}
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			// ocserv answers a wrong OTP this way. Never retried: a
			// reconnect loop must not lock the account out.
			return nil, nil, rejected("login failed (HTTP %d)", resp.StatusCode)
		}
		if resp.StatusCode == http.StatusNotFound {
			// Retrying won't help; usually the Server field has a path
			// (a group URL or a copied portal address) the gateway lacks.
			return nil, nil, rejected("gateway returned HTTP 404 for %s; check that Server is exactly what Cisco's app shows in \"Connect to\" (usually just the host name)",
				target.Redacted())
		}
		if resp.StatusCode != http.StatusOK {
			return nil, nil, fmt.Errorf("gateway returned HTTP %d for %s", resp.StatusCode, target.Redacted())
		}
		return target, data, nil
	}
	return nil, nil, errors.New("too many redirects")
}

// --- the auth conversation --------------------------------------------------

type authState struct {
	passwordSent    bool
	groupChosen     bool
	secondFactorRnd int
	totp            *totp
	serverMessage   string // <message> of the current round, for prompts and errors
	serverTitle     string // <title> of the current round, for prompts
}

// authenticate runs Cisco's aggregate XML authentication and returns the
// session cookie (the value of the `webvpn` cookie / <session-token>) that
// the CSTP CONNECT presents. Handles the shapes seen in practice: ASA's
// single username+password form, ocserv's username-then-password rounds,
// group selection, and an OTP / RADIUS-challenge second round.
func (a *authClient) authenticate(ctx context.Context) (string, error) {
	st := &authState{}
	if a.cfg.OTPSecret != "" {
		st.totp, _ = newTOTP(a.cfg.OTPSecret)
	}
	target := a.base
	body := initRequest(a.base, false)
	certDeclined := false
	for round := 0; round < 8; round++ {
		finalURL, data, err := a.post(ctx, target, body)
		if err != nil {
			return "", err
		}
		var ca xmlConfigAuth
		if err := xml.Unmarshal(data, &ca); err != nil {
			return "", rejected("gateway did not answer with AnyConnect XML auth (is this an AnyConnect/ocserv server?): %v", err)
		}
		switch ca.Type {
		case "complete":
			token := strings.TrimSpace(ca.SessionToken)
			if token == "" {
				token = a.cookie("webvpn")
			}
			if token == "" {
				return "", errors.New("authentication completed without a session cookie")
			}
			return token, nil
		case "auth-request":
			// handled below
		default:
			return "", fmt.Errorf("unexpected config-auth type %q", ca.Type)
		}

		if ca.Auth.Error != nil && ca.Auth.Error.message() != "" {
			return "", rejected("login failed: %s", ca.Auth.Error.message())
		}
		// ocserv reports a failed attempt in <message> ("Login failed.") and
		// re-shows the form, rather than using <error> like an ASA.
		if msg := strings.TrimSpace(ca.Auth.Message); looksLikeFailure(msg) {
			return "", rejected("login failed: %s", strings.Join(strings.Fields(msg), " "))
		}
		st.serverMessage = strings.TrimSpace(ca.Auth.Message)
		st.serverTitle = strings.TrimSpace(ca.Auth.Title)
		if ca.ClientCertRequest != nil {
			// Many ASAs ask every client for a certificate first and fall
			// back to the login form when told there is none. Say so once,
			// like openconnect: the same init, plus <client-cert-fail/>.
			if !certDeclined {
				certDeclined = true
				target = a.base
				body = initRequest(a.base, true)
				continue
			}
			return "", rejected("gateway requires a client certificate, which Haven's AnyConnect tunnel does not support yet")
		}
		if strings.TrimSpace(ca.Auth.SSOLogin) != "" {
			return "", rejected("gateway requires SAML/SSO browser login, which Haven's AnyConnect tunnel does not support yet")
		}
		if ca.Auth.Form == nil {
			msg := strings.TrimSpace(ca.Auth.Message)
			if msg == "" {
				msg = "no login form"
			}
			return "", rejected("gateway sent an auth request Haven can't answer: %s", msg)
		}

		action, err := finalURL.Parse(ca.Auth.Form.Action)
		if err != nil || ca.Auth.Form.Action == "" {
			action = finalURL
		}
		target = action
		opaque := ""
		if m := opaqueRE.Find(data); m != nil {
			opaque = string(m)
		}
		body, err = a.buildReply(ca.Auth.Form, opaque, st)
		if err != nil {
			return "", err
		}
	}
	return "", rejected("authentication did not complete after 8 rounds")
}

func (a *authClient) cookie(name string) string {
	for _, c := range a.client.Jar.Cookies(a.base) {
		if c.Name == name {
			return c.Value
		}
	}
	return ""
}

// authOverallTimeout bounds a whole sign-in. Generous because a person may
// be typing an SMS code; each HTTP request has its own 30 s timeout and the
// gateway its own auth timeout (ocserv 240 s, ASA similar).
const authOverallTimeout = 10 * time.Minute

// authenticate runs a full sign-in with an optional prompter.
func authenticate(cfg *Config, base *url.URL, tlsConf *tls.Config, p Prompter) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), authOverallTimeout)
	defer cancel()
	ac := newAuthClient(cfg, base, tlsConf)
	ac.prompter = p
	defer ac.client.CloseIdleConnections()
	return ac.authenticate(ctx)
}

// initRequest is the opening <config-auth type="init">. certFail declines a
// gateway's request for a client certificate.
func initRequest(base *url.URL, certFail bool) string {
	var b bytes.Buffer
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<config-auth client="vpn" type="init" aggregate-auth-version="2">`)
	b.WriteString(`<version who="vpn">v9.12</version>`)
	b.WriteString(`<device-id>linux-64</device-id>`)
	b.WriteString(`<group-access>`)
	xml.EscapeText(&b, []byte(base.String()))
	b.WriteString(`</group-access>`)
	if certFail {
		b.WriteString(`<client-cert-fail/>`)
	}
	b.WriteString(`</config-auth>`)
	return b.String()
}

// buildReply fills a login form from the config. A dedicated round is used
// to switch auth group when the requested one isn't pre-selected, because
// an ASA answers a group change with a fresh form for that group and
// ignores credentials posted alongside it.
func (a *authClient) buildReply(form *xmlForm, opaque string, st *authState) (string, error) {
	type field struct{ name, value string }
	var fields []field
	groupSelect := ""

	for _, sel := range form.Selects {
		if len(sel.Options) == 0 {
			continue
		}
		current := sel.Options[0]
		for _, o := range sel.Options {
			if o.Selected != "" {
				current = o
				break
			}
		}
		if sel.Name != groupListField {
			fields = append(fields, field{sel.Name, current.value()})
			continue
		}
		chosen := current
		if a.cfg.AuthGroup != "" {
			found := false
			var names []string
			for _, o := range sel.Options {
				names = append(names, o.value())
				if strings.EqualFold(o.value(), a.cfg.AuthGroup) ||
					strings.EqualFold(strings.TrimSpace(o.Text), a.cfg.AuthGroup) {
					chosen, found = o, true
					break
				}
			}
			if !found {
				return "", rejected("auth group %q not offered by gateway (available: %s)",
					a.cfg.AuthGroup, strings.Join(names, ", "))
			}
		}
		groupSelect = chosen.value()
		if !st.groupChosen && chosen.value() != current.value() {
			st.groupChosen = true
			return replyXML(opaque, nil, groupSelect), nil
		}
		st.groupChosen = true
	}

	for _, in := range form.Inputs {
		if in.Name == "" {
			continue
		}
		name := strings.ToLower(in.Name)
		switch strings.ToLower(in.Type) {
		case "submit", "reset", "button", "image":
			continue
		case "hidden":
			fields = append(fields, field{in.Name, in.Value})
		case "password":
			if !st.passwordSent && !looksLikeSecondFactor(name) {
				pw := a.cfg.Password
				if pw == "" {
					// No stored password: ask, so users who'd rather not keep
					// it on the phone can still use the tunnel.
					if v, ok := a.ask(in, st, true); ok {
						if v == "" {
							return "", rejected("sign-in cancelled")
						}
						pw = v
					}
				}
				fields = append(fields, field{in.Name, pw})
				st.passwordSent = true
				continue
			}
			v, err := a.secondFactor(in, st)
			if err != nil {
				return "", err
			}
			fields = append(fields, field{in.Name, v})
		default: // text, email, tel, empty
			if looksLikeSecondFactor(name) {
				v, err := a.secondFactor(in, st)
				if err != nil {
					return "", err
				}
				fields = append(fields, field{in.Name, v})
				continue
			}
			fields = append(fields, field{in.Name, a.cfg.Username})
		}
	}

	out := make([][2]string, 0, len(fields))
	for _, f := range fields {
		out = append(out, [2]string{f.name, f.value})
	}
	return replyXML(opaque, out, groupSelect), nil
}

func looksLikeFailure(msg string) bool {
	m := strings.ToLower(msg)
	for _, k := range []string{"fail", "incorrect", "invalid", "denied", "wrong", "expired", "locked"} {
		if strings.Contains(m, k) {
			return true
		}
	}
	return false
}

func looksLikeSecondFactor(name string) bool {
	for _, k := range []string{"secondary", "otp", "token", "passcode", "answer", "challenge", "pin", "verif"} {
		if strings.Contains(name, k) {
			return true
		}
	}
	return false
}

func (a *authClient) secondFactor(in xmlInput, st *authState) (string, error) {
	st.secondFactorRnd++
	if st.secondFactorRnd > 2 {
		return "", rejected("gateway keeps asking for a second factor (%s); check the OTP secret / secondary password",
			promptName(in))
	}
	if st.totp != nil {
		return st.totp.codeAt(time.Now()), nil
	}
	if a.cfg.SecondaryPassword != "" {
		return a.cfg.SecondaryPassword, nil
	}
	// An SMS / e-mail / hardware-token code: only the user has it.
	// Not masked — it's single-use, and easier to type when visible.
	if v, ok := a.ask(in, st, false); ok {
		if strings.TrimSpace(v) == "" {
			return "", rejected("sign-in cancelled")
		}
		return strings.TrimSpace(v), nil
	}
	ctx := ""
	if st.serverMessage != "" {
		ctx = fmt.Sprintf(" — server said %q", strings.Join(strings.Fields(st.serverMessage), " "))
	}
	return "", rejected("gateway asked for a second factor (%s)%s; add an OTP secret or a secondary password to this tunnel",
		promptName(in), ctx)
}

func promptName(in xmlInput) string {
	if l := strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(in.Label), ":")); l != "" {
		return l
	}
	return in.Name
}

func replyXML(opaque string, fields [][2]string, groupSelect string) string {
	var b bytes.Buffer
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<config-auth client="vpn" type="auth-reply" aggregate-auth-version="2">`)
	b.WriteString(`<version who="vpn">v9.12</version>`)
	b.WriteString(`<device-id>linux-64</device-id>`)
	b.WriteString(`<session-token/><session-id/>`)
	b.WriteString(opaque)
	b.WriteString(`<auth>`)
	for _, f := range fields {
		if !validXMLName(f[0]) {
			continue
		}
		b.WriteString("<" + f[0] + ">")
		xml.EscapeText(&b, []byte(f[1]))
		b.WriteString("</" + f[0] + ">")
	}
	b.WriteString(`</auth>`)
	if groupSelect != "" {
		b.WriteString(`<group-select>`)
		xml.EscapeText(&b, []byte(groupSelect))
		b.WriteString(`</group-select>`)
	}
	b.WriteString(`</config-auth>`)
	return b.String()
}

// validXMLName keeps a hostile form from injecting markup through a field
// name we echo back as an element name.
var xmlNameRE = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_.-]*$`)

func validXMLName(s string) bool { return xmlNameRE.MatchString(s) }

// logout ends the server-side session so it doesn't linger until the idle
// timeout and count against a per-user session limit. Best-effort.
func logout(cfg *Config, base *url.URL, tlsConf *tls.Config, cookie string) {
	ac := newAuthClient(cfg, base, tlsConf)
	ac.client.Timeout = 5 * time.Second
	u, err := base.Parse("/+webvpn+/webvpn_logout.html")
	if err != nil {
		return
	}
	req, err := http.NewRequest(http.MethodGet, u.String(), nil)
	if err != nil {
		return
	}
	setCommonHeaders(req.Header, cfg.UserAgent)
	req.Header.Set("Cookie", "webvpn="+cookie)
	if resp, err := ac.client.Do(req); err == nil {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
		resp.Body.Close()
	}
	ac.client.CloseIdleConnections()
}
