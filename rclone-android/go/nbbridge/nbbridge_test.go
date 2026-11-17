package nbbridge

import (
	"encoding/json"
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

// setRosenpassInConfig must set the flag without disturbing the rest of the
// config: a reconnect's persisted PrivateKey is the peer identity, and clobbering
// it would re-register the peer and burn the setup key again. This guards the
// merge, not the end-to-end engine behaviour (that needs a live NetBird network
// on Android and is verified on-device, not here).
func TestSetRosenpassInConfig(t *testing.T) {
	path := t.TempDir() + "/config.json"

	read := func() map[string]json.RawMessage {
		b, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read config: %v", err)
		}
		var m map[string]json.RawMessage
		if err := json.Unmarshal(b, &m); err != nil {
			t.Fatalf("unmarshal config: %v", err)
		}
		return m
	}

	// Absent file: created with the flag set, no other keys invented.
	if err := setRosenpassInConfig(path, true); err != nil {
		t.Fatalf("create: %v", err)
	}
	if got := string(read()["RosenpassEnabled"]); got != "true" {
		t.Fatalf("RosenpassEnabled = %s, want true", got)
	}

	// Existing identity preserved across a toggle to false.
	seed := []byte(`{"PrivateKey":"SECRETKEY","RosenpassEnabled":true}`)
	if err := os.WriteFile(path, seed, 0o600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := setRosenpassInConfig(path, false); err != nil {
		t.Fatalf("toggle off: %v", err)
	}
	m := read()
	if got := string(m["RosenpassEnabled"]); got != "false" {
		t.Fatalf("after toggle off RosenpassEnabled = %s, want false", got)
	}
	if got := string(m["PrivateKey"]); got != `"SECRETKEY"` {
		t.Fatalf("PrivateKey clobbered: %s", got)
	}
}
