package nbbridge

import (
	"os"
	"testing"
)

// The on-device repro (see #637) showed netbird's linked-in bind package
// rejects wireguard-go UDP binds on Android unless NB_USE_NETSTACK_MODE is
// set before any bind happens. This package's init sets it; this test fails
// if that init is removed or renamed. It cannot reproduce the failure itself
// on linux — the Android control registration is behind GOOS=android build
// tags — so the env assertion is the regression guard here.
func TestNetstackModeSetForNetbirdClient(t *testing.T) {
	if got := os.Getenv("NB_USE_NETSTACK_MODE"); got != "true" {
		t.Fatalf("NB_USE_NETSTACK_MODE = %q, want %q (set by this package's init)", got, "true")
	}
}