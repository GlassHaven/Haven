//go:build linux && amd64 && !android

package openvpn3

/*
// Host tests use the adapter built by openvpn3-core/build-host-test.sh.
// Android also satisfies the linux build tag, so this file explicitly
// excludes it to keep the host archive out of Android x86_64 links.
// cgo links with the C compiler, so the C++ runtime must be explicit.
#cgo LDFLAGS: -L${SRCDIR}/../../../../openvpn3-core/host-test-lib -lopenvpn3_host_amd64 -lm -lstdc++
*/
import "C"
