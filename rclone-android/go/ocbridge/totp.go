package ocbridge

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base32"
	"encoding/binary"
	"errors"
	"fmt"
	"hash"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// totp is an RFC 6238 generator. Answers a gateway's second-factor prompt
// with a fresh code on every (re-)authentication, which is what lets a
// tunnel reconnect unattended after the server expires its session.
type totp struct {
	key    []byte
	digits int
	period int64
	algo   func() hash.Hash
}

// newTOTP accepts a bare base32 secret (spaces, dashes and padding
// tolerated) or an otpauth://totp/ URI.
func newTOTP(s string) (*totp, error) {
	t := &totp{digits: 6, period: 30, algo: sha1.New}
	secret := s
	if strings.HasPrefix(strings.ToLower(s), "otpauth://") {
		u, err := url.Parse(s)
		if err != nil {
			return nil, fmt.Errorf("invalid otpauth URI: %w", err)
		}
		if !strings.EqualFold(u.Host, "totp") {
			return nil, fmt.Errorf("only otpauth://totp/ is supported, got %q", u.Host)
		}
		q := u.Query()
		secret = q.Get("secret")
		if d := q.Get("digits"); d != "" {
			n, err := strconv.Atoi(d)
			if err != nil || n < 6 || n > 8 {
				return nil, fmt.Errorf("invalid digits %q", d)
			}
			t.digits = n
		}
		if p := q.Get("period"); p != "" {
			n, err := strconv.ParseInt(p, 10, 64)
			if err != nil || n <= 0 {
				return nil, fmt.Errorf("invalid period %q", p)
			}
			t.period = n
		}
		switch strings.ToUpper(q.Get("algorithm")) {
		case "", "SHA1":
		case "SHA256":
			t.algo = sha256.New
		case "SHA512":
			t.algo = sha512.New
		default:
			return nil, fmt.Errorf("unsupported algorithm %q", q.Get("algorithm"))
		}
	}
	clean := strings.ToUpper(strings.NewReplacer(" ", "", "-", "", "=", "").Replace(secret))
	if clean == "" {
		return nil, errors.New("empty secret")
	}
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(clean)
	if err != nil {
		return nil, errors.New("secret is not valid base32")
	}
	t.key = key
	return t, nil
}

func (t *totp) codeAt(now time.Time) string {
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], uint64(now.Unix()/t.period))
	mac := hmac.New(t.algo, t.key)
	mac.Write(msg[:])
	sum := mac.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	bin := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	mod := uint32(1)
	for i := 0; i < t.digits; i++ {
		mod *= 10
	}
	return fmt.Sprintf("%0*d", t.digits, bin%mod)
}
