// Package netbind binds outbound underlay sockets and DNS lookups to a specific
// Android Network (android.net.Network.getNetworkHandle / net_handle_t, #876).
//
// When a phone is connected to a LAN-only Wi-Fi with no internet route (or an
// active full-device VPN overrides the default route), default sockets and DNS
// ride mobile data / VPN instead of the Wi-Fi link. Binding individual tunnel
// underlay sockets via android_setsocknetwork and resolving endpoint hostnames
// via android_getaddrinfofornetwork routes that tunnel's underlay over the
// chosen physical network without claiming the Android VpnService slot or
// changing the process-wide network binding.
package netbind

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"syscall"
)

var (
	bindHook   = platformBindSocket
	lookupHook = platformLookupHost
)

// SetBindHookForTest swaps the low-level socket bind implementation for unit
// tests and returns a restore function.
func SetBindHookForTest(fn func(networkHandle uint64, fd uintptr) error) func() {
	prev := bindHook
	bindHook = fn
	return func() { bindHook = prev }
}

// SetLookupHookForTest swaps the per-network DNS lookup implementation for unit
// tests and returns a restore function.
func SetLookupHookForTest(fn func(host string, networkHandle uint64) ([]string, error)) func() {
	prev := lookupHook
	lookupHook = func(_ context.Context, networkHandle uint64, host string) ([]string, error) {
		return fn(host, networkHandle)
	}
	return func() { lookupHook = prev }
}

// Control returns a net.Dialer / net.ListenConfig Control callback that binds
// created sockets to networkHandle, or nil when networkHandle == 0 (default route).
func Control(networkHandle uint64) func(network, address string, c syscall.RawConn) error {
	if networkHandle == 0 {
		return nil
	}
	return func(_, _ string, c syscall.RawConn) error {
		var bindErr error
		if err := c.Control(func(fd uintptr) {
			bindErr = bindHook(networkHandle, fd)
		}); err != nil {
			return err
		}
		return bindErr
	}
}

// LookupHost resolves host using networkHandle's DNS servers when non-zero, or
// Go's default resolver when networkHandle == 0.
func LookupHost(ctx context.Context, networkHandle uint64, host string) ([]string, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if networkHandle == 0 {
		return net.DefaultResolver.LookupHost(ctx, host)
	}
	return lookupHook(ctx, networkHandle, host)
}

// ResolveHostPort resolves the host component of host:port on networkHandle
// when host is not already an IP literal, preferring IPv4 over IPv6.
func ResolveHostPort(ctx context.Context, networkHandle uint64, addr string) (string, error) {
	if networkHandle == 0 {
		return addr, nil
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", err
	}
	if _, err := netip.ParseAddr(host); err == nil {
		return addr, nil
	}
	addrs, err := LookupHost(ctx, networkHandle, host)
	if err != nil {
		return "", fmt.Errorf("resolve %q on bound network: %w", host, err)
	}
	var chosen netip.Addr
	for _, a := range addrs {
		ip, err := netip.ParseAddr(a)
		if err != nil {
			continue
		}
		if ip.Is4() {
			chosen = ip
			break
		}
		if !chosen.IsValid() {
			chosen = ip
		}
	}
	if !chosen.IsValid() {
		return "", fmt.Errorf("resolve %q on bound network: no usable IP in %v", host, addrs)
	}
	return net.JoinHostPort(chosen.String(), port), nil
}
