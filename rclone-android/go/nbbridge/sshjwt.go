package nbbridge

import (
	"context"
	"time"
)

// SSHAuthInfo carries the NetBird SSH JWT OIDC device-flow details for the UI
// (#875). Open VerificationURIComplete in a browser, show UserCode, then call
// WaitSSHAuth(DeviceCode). The returned JWT is the SSH password for a NetBird
// SSH server.
type SSHAuthInfo struct {
	VerificationURI         string
	VerificationURIComplete string
	UserCode                string
	DeviceCode              string
	ExpiresIn               int64
}

// StartSSHAuth begins the NetBird SSH JWT device flow and returns the browser
// verification URI and user code. hint optionally pre-selects the IdP login.
func (t *TunnelHandle) StartSSHAuth(hint string, timeoutMs int) (*SSHAuthInfo, error) {
	c, err := t.client()
	if err != nil {
		return nil, err
	}
	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	info, err := c.RequestSSHAuth(ctx, hint)
	if err != nil {
		return nil, err
	}
	return &SSHAuthInfo{
		VerificationURI:         info.VerificationURI,
		VerificationURIComplete: info.VerificationURIComplete,
		UserCode:                info.UserCode,
		DeviceCode:              info.DeviceCode,
		ExpiresIn:               info.ExpiresIn,
	}, nil
}

// WaitSSHAuth blocks until the user completes the browser authentication for
// the device flow started by StartSSHAuth, then returns the JWT to use as the
// SSH password. timeoutMs should be generous (the user must finish logging in);
// defaults to 5 minutes.
func (t *TunnelHandle) WaitSSHAuth(deviceCode string, timeoutMs int) (string, error) {
	c, err := t.client()
	if err != nil {
		return "", err
	}
	if timeoutMs <= 0 {
		timeoutMs = 300_000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	return c.WaitSSHAuth(ctx, deviceCode)
}

// CachedSSHJWT returns a still-valid JWT from a prior WaitSSHAuth, or empty.
func (t *TunnelHandle) CachedSSHJWT() string {
	c, err := t.client()
	if err != nil {
		return ""
	}
	return c.CachedSSHJWT()
}