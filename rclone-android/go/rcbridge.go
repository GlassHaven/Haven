// Package rcbridge provides a minimal Go bridge to librclone for use via
// gomobile on Android.
package rcbridge

import (
	"os"
	"path/filepath"

	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/fs/config/configfile"
	"github.com/rclone/rclone/librclone/librclone"

	// Import rclone backends and operations so their RC methods get registered.
	_ "github.com/rclone/rclone/backend/all"
	_ "github.com/rclone/rclone/fs/operations"
	_ "github.com/rclone/rclone/fs/sync"
)

func init() {
	// Set a fallback HOME on Android to suppress "getent not found" errors
	// from rclone's config directory detection. RbInitialize overrides this
	// with the real app-specific path.
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", "/tmp")
	}
}

// RbResult holds the output of an RC method call.
type RbResult struct {
	Status int64
	Output string
}

// RbInitialize initialises the rclone library.
//
// configPath is the absolute path to the rclone config file.
func RbInitialize(configPath string) {
	if configPath != "" {
		dir := filepath.Dir(configPath)
		os.MkdirAll(dir, 0700)
		// Set the config path BEFORE Initialize so rclone uses it.
		config.SetConfigPath(configPath)
		os.Setenv("HOME", dir)
	}
	os.Setenv("RCLONE_LOG_LEVEL", "NOTICE")
	librclone.Initialize()
	// Install the file-based config backend so config is read/written to disk.
	configfile.Install()
}

// RbFinalize tears down the rclone library.
func RbFinalize() {
	librclone.Finalize()
}

// RbRPC calls an rclone RC method with a JSON input string and returns
// the result.
func RbRPC(method, input string) *RbResult {
	output, status := librclone.RPC(method, input)
	return &RbResult{
		Status: int64(status),
		Output: output,
	}
}
