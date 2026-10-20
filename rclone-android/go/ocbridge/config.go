package ocbridge

import (
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"
)

// Config is the JSON envelope Haven stores (encrypted at rest) for an
// AnyConnect tunnel. Field names match sh.haven.core.tunnel.AnyConnectConfigBlob
// on the Kotlin side; unknown keys are ignored so the two can evolve
// independently.
type Config struct {
	// Server is the VPN gateway, as users type it into Cisco's client:
	// "vpn.example.com", "vpn.example.com:8443", or a group URL such as
	// "https://vpn.example.com/engineering". https:// is implied.
	Server string `json:"server"`

	Username string `json:"username"`
	Password string `json:"password"`

	// AuthGroup selects an entry from the server's group_list dropdown
	// (ASA "tunnel-group" / ocserv "select-group"). Matched against the
	// option value or its visible label, case-insensitively. Empty keeps
	// the server's default.
	AuthGroup string `json:"authGroup,omitempty"`

	// OTPSecret answers a second-factor prompt with a fresh TOTP code. Either
	// a base32 secret or a full otpauth://totp/... URI (digits, period and
	// algorithm are honoured).
	OTPSecret string `json:"otpSecret,omitempty"`

	// SecondaryPassword answers a second-factor prompt with a fixed string,
	// e.g. "push" for Duo push. Ignored when OTPSecret is set.
	SecondaryPassword string `json:"secondaryPassword,omitempty"`

	// ServerCert pins the gateway certificate instead of validating it
	// against the system CA store, for self-signed ocserv deployments.
	// Same formats openconnect's --servercert accepts:
	//   pin-sha256:<base64 SHA-256 of the SubjectPublicKeyInfo>
	//   sha256:<hex SHA-256 of the DER certificate>  (or bare 64 hex chars)
	//   sha1:<hex SHA-1 of the DER certificate>      (or bare 40 hex chars)
	ServerCert string `json:"serverCert,omitempty"`

	// UserAgent overrides the HTTP User-Agent. Some ASA policies only admit
	// clients that identify as Cisco's ("AnyConnect Linux_64 4.10.07061").
	UserAgent string `json:"userAgent,omitempty"`
}

// defaultUserAgent identifies as openconnect, whose protocol behaviour this
// package follows (aggregate XML auth, CSTP over TLS, no DTLS/compression).
// Both ocserv and ASA accept it by default.
const defaultUserAgent = "Open AnyConnect VPN Agent v9.12"

func parseConfig(text string) (*Config, error) {
	var c Config
	if err := json.Unmarshal([]byte(strings.TrimSpace(text)), &c); err != nil {
		return nil, fmt.Errorf("config is not valid JSON: %w", err)
	}
	c.Server = strings.TrimSpace(c.Server)
	c.Username = strings.TrimSpace(c.Username)
	c.AuthGroup = strings.TrimSpace(c.AuthGroup)
	c.OTPSecret = strings.TrimSpace(c.OTPSecret)
	c.ServerCert = strings.TrimSpace(c.ServerCert)
	c.UserAgent = strings.TrimSpace(c.UserAgent)
	if c.Server == "" {
		return nil, errors.New("missing server")
	}
	if c.Username == "" {
		return nil, errors.New("missing username")
	}
	if c.UserAgent == "" {
		c.UserAgent = defaultUserAgent
	}
	if c.OTPSecret != "" {
		if _, err := newTOTP(c.OTPSecret); err != nil {
			return nil, fmt.Errorf("otpSecret: %w", err)
		}
	}
	if c.ServerCert != "" {
		if _, err := parsePin(c.ServerCert); err != nil {
			return nil, fmt.Errorf("serverCert: %w", err)
		}
	}
	return &c, nil
}

// normalizeServer turns what a user typed into the base URL every request
// is made against. The path (a group URL) is preserved: ASA routes the
// initial config-auth POST to the tunnel-group bound to that path.
func normalizeServer(s string) (*url.URL, error) {
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil {
		return nil, fmt.Errorf("invalid server %q: %w", s, err)
	}
	if u.Scheme != "https" {
		return nil, fmt.Errorf("server must use https, got %q", u.Scheme)
	}
	if u.Hostname() == "" {
		return nil, fmt.Errorf("server %q has no host", s)
	}
	if u.Path == "" {
		u.Path = "/"
	}
	u.RawQuery = ""
	u.Fragment = ""
	return u, nil
}

// hostPort is the TLS dial target for the base URL, defaulting to 443.
func hostPort(u *url.URL) string {
	if u.Port() != "" {
		return u.Host
	}
	return net.JoinHostPort(u.Hostname(), "443")
}

type pinKind int

const (
	pinSPKISHA256 pinKind = iota
	pinCertSHA256
	pinCertSHA1
)

type certPin struct {
	kind pinKind
	hash []byte
}

func parsePin(s string) (*certPin, error) {
	lower := strings.ToLower(s)
	switch {
	case strings.HasPrefix(lower, "pin-sha256:"):
		b, err := base64.StdEncoding.DecodeString(s[len("pin-sha256:"):])
		if err != nil || len(b) != sha256.Size {
			return nil, errors.New("pin-sha256 must be the base64 of a 32-byte SHA-256")
		}
		return &certPin{pinSPKISHA256, b}, nil
	case strings.HasPrefix(lower, "sha256:"):
		return parseHexPin(lower[len("sha256:"):], pinCertSHA256, sha256.Size)
	case strings.HasPrefix(lower, "sha1:"):
		return parseHexPin(lower[len("sha1:"):], pinCertSHA1, sha1.Size)
	}
	clean := strings.ReplaceAll(lower, ":", "")
	switch len(clean) {
	case 2 * sha256.Size:
		return parseHexPin(clean, pinCertSHA256, sha256.Size)
	case 2 * sha1.Size:
		return parseHexPin(clean, pinCertSHA1, sha1.Size)
	}
	return nil, errors.New("unrecognised format; use pin-sha256:<base64>, sha256:<hex> or sha1:<hex>")
}

func parseHexPin(h string, kind pinKind, size int) (*certPin, error) {
	b, err := hex.DecodeString(strings.ReplaceAll(h, ":", ""))
	if err != nil || len(b) != size {
		return nil, fmt.Errorf("expected %d hex characters", 2*size)
	}
	return &certPin{kind, b}, nil
}

func (p *certPin) matches(cert *x509.Certificate) bool {
	var got []byte
	switch p.kind {
	case pinSPKISHA256:
		h := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
		got = h[:]
	case pinCertSHA256:
		h := sha256.Sum256(cert.Raw)
		got = h[:]
	case pinCertSHA1:
		h := sha1.Sum(cert.Raw)
		got = h[:]
	}
	return subtle.ConstantTimeCompare(got, p.hash) == 1
}

// spkiPin formats a certificate the way an error message should suggest
// pinning it, so a user with a self-signed gateway can copy it verbatim.
func spkiPin(cert *x509.Certificate) string {
	h := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return "pin-sha256:" + base64.StdEncoding.EncodeToString(h[:])
}

// tlsConfigFor builds the client TLS config. With no pin the system CA
// store validates the gateway as usual. With a pin, chain validation is
// replaced by an exact match on the leaf — the openconnect --servercert
// model — so a self-signed ocserv works without installing a CA.
func tlsConfigFor(c *Config, base *url.URL) *tls.Config {
	conf := &tls.Config{
		ServerName: base.Hostname(),
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"http/1.1"},
	}
	if c.ServerCert == "" {
		// Fail with an actionable message when the chain doesn't verify:
		// the standard error doesn't tell the user what to pin.
		conf.InsecureSkipVerify = true
		conf.VerifyConnection = func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("server presented no certificate")
			}
			opts := x509.VerifyOptions{
				DNSName:       conf.ServerName,
				Intermediates: x509.NewCertPool(),
			}
			for _, ic := range cs.PeerCertificates[1:] {
				opts.Intermediates.AddCert(ic)
			}
			if _, err := cs.PeerCertificates[0].Verify(opts); err != nil {
				return fmt.Errorf("server certificate not trusted (%v); if this is your own gateway, set Server certificate to %s",
					err, spkiPin(cs.PeerCertificates[0]))
			}
			return nil
		}
		return conf
	}
	pin, _ := parsePin(c.ServerCert) // validated in parseConfig
	conf.InsecureSkipVerify = true
	conf.VerifyConnection = func(cs tls.ConnectionState) error {
		if len(cs.PeerCertificates) == 0 {
			return errors.New("server presented no certificate")
		}
		if !pin.matches(cs.PeerCertificates[0]) {
			return fmt.Errorf("server certificate does not match the pinned value (server presents %s)",
				spkiPin(cs.PeerCertificates[0]))
		}
		return nil
	}
	return conf
}
