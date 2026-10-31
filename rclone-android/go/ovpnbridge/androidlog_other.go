//go:build !android

package ovpnbridge

import "log"

// androidLog falls back to the standard logger off-Android (the host test
// binary, notably TestStartTunnel_RealHandshake) so the same diagnostics
// are visible in `go test -v` output too.
func androidLog(tag, msg string) {
	log.Printf("[%s] %s", tag, msg)
}
