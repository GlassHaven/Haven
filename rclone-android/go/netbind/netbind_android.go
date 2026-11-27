//go:build android

package netbind

/*
#cgo LDFLAGS: -landroid

#include <android/multinetwork.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

static int haven_setsocknetwork(uint64_t net_handle, int fd) {
    if (android_setsocknetwork((net_handle_t)net_handle, fd) != 0) {
        int e = errno;
        return e > 0 ? -e : -EINVAL;
    }
    return 0;
}
*/
import "C"

import (
	"context"
	"fmt"
	"net/netip"
	"syscall"
	"unsafe"
)

func platformBindSocket(networkHandle uint64, fd uintptr) error {
	rc := C.haven_setsocknetwork(C.uint64_t(networkHandle), C.int(fd))
	if rc != 0 {
		return fmt.Errorf("android_setsocknetwork(%#x): %w", networkHandle, syscall.Errno(-rc))
	}
	return nil
}

func platformLookupHost(_ context.Context, networkHandle uint64, host string) ([]string, error) {
	cHost := C.CString(host)
	defer C.free(unsafe.Pointer(cHost))

	var hints C.struct_addrinfo
	hints.ai_family = C.AF_UNSPEC
	hints.ai_socktype = C.SOCK_STREAM

	var res *C.struct_addrinfo
	rc := C.android_getaddrinfofornetwork(
		C.net_handle_t(networkHandle),
		cHost,
		nil,
		&hints,
		&res,
	)
	if rc != 0 {
		return nil, fmt.Errorf("android_getaddrinfofornetwork(%q): %s", host, C.GoString(C.gai_strerror(rc)))
	}
	defer C.freeaddrinfo(res)

	var out []string
	seen := map[string]struct{}{}
	for cur := res; cur != nil; cur = cur.ai_next {
		if cur.ai_addr == nil {
			continue
		}
		var ip netip.Addr
		switch cur.ai_family {
		case C.AF_INET:
			sin := (*C.struct_sockaddr_in)(unsafe.Pointer(cur.ai_addr))
			b := *(*[4]byte)(unsafe.Pointer(&sin.sin_addr))
			ip = netip.AddrFrom4(b)
		case C.AF_INET6:
			sin6 := (*C.struct_sockaddr_in6)(unsafe.Pointer(cur.ai_addr))
			b := *(*[16]byte)(unsafe.Pointer(&sin6.sin6_addr))
			ip = netip.AddrFrom16(b)
		default:
			continue
		}
		s := ip.String()
		if _, dup := seen[s]; !dup {
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("android_getaddrinfofornetwork(%q): no addresses", host)
	}
	return out, nil
}
