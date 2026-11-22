package rcbridge

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

	"github.com/rclone/rclone/fs"
)

// TestOptionsSetRaisesConnectResilience proves the runtime path Haven uses to
// make storage first-connects more reliable (#839): the RC "options/set" method
// with a "main" block mutates the live global config that backends read, so the
// Kotlin client can raise the connect timeout and retry counts after Initialize
// without a native rebuild. Asserts the values actually changed from rclone's
// defaults (a gate that cannot fail is not a test).
func TestOptionsSetRaisesConnectResilience(t *testing.T) {
	RbInitialize(filepath.Join(t.TempDir(), "rclone.conf"))
	defer RbFinalize()

	cfg := fs.GetConfig(context.Background())
	beforeLLR := cfg.LowLevelRetries
	beforeRetries := cfg.Retries
	beforeConnect := cfg.ConnectTimeout

	res := RbRPC("options/set", `{"main":{"ConnectTimeout":"90s","LowLevelRetries":20,"Retries":5}}`)
	if res.Status != 200 {
		t.Fatalf("options/set status=%d output=%s", res.Status, res.Output)
	}

	if cfg.LowLevelRetries != 20 {
		t.Errorf("LowLevelRetries = %d, want 20", cfg.LowLevelRetries)
	}
	if cfg.Retries != 5 {
		t.Errorf("Retries = %d, want 5", cfg.Retries)
	}
	if cfg.ConnectTimeout != fs.Duration(90*time.Second) {
		t.Errorf("ConnectTimeout = %v, want 1m30s", cfg.ConnectTimeout)
	}
	// The change must be real, not a no-op that happens to match the default.
	if beforeLLR == 20 && beforeRetries == 5 && beforeConnect == fs.Duration(90*time.Second) {
		t.Fatalf("defaults already equal the target values; test cannot prove a change")
	}

	// Round-trip through options/get to confirm the RPC surface reports them too.
	get := RbRPC("options/get", `{}`)
	if get.Status != 200 {
		t.Fatalf("options/get status=%d output=%s", get.Status, get.Output)
	}
	var blocks map[string]map[string]any
	if err := json.Unmarshal([]byte(get.Output), &blocks); err != nil {
		t.Fatalf("options/get parse: %v output=%s", err, get.Output)
	}
	main := blocks["main"]
	if main == nil {
		t.Fatalf("options/get missing main block: %s", get.Output)
	}
	if got := main["LowLevelRetries"]; got != float64(20) {
		t.Errorf("options/get LowLevelRetries = %v, want 20", got)
	}
	if got := main["Retries"]; got != float64(5) {
		t.Errorf("options/get Retries = %v, want 5", got)
	}
}