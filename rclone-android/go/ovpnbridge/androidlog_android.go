//go:build android

package ovpnbridge

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>
*/
import "C"
import "unsafe"

// androidLog writes to logcat under the given tag (visible via
// `adb logcat -s <tag>`). OpenVPN3 Core's own log() and event() callbacks
// otherwise have nowhere to surface diagnostics on Android -- there is no
// stdout/stderr any developer is watching, unlike the host test binary.
func androidLog(tag, msg string) {
	ctag := C.CString(tag)
	cmsg := C.CString(msg)
	defer C.free(unsafe.Pointer(ctag))
	defer C.free(unsafe.Pointer(cmsg))
	C.__android_log_write(C.ANDROID_LOG_DEBUG, ctag, cmsg)
}
