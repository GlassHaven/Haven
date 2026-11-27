package netbind

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
)

func TestControlNoopWhenUnspecified(t *testing.T) {
	if Control(0) != nil {
		t.Fatal("Control(0) must be nil so default-route dials add no syscall hook")
	}
}

func TestControlInvokesBindHookWithSocketFD(t *testing.T) {
	var seenHandle atomic.Uint64
	var seenFD atomic.Uint64
	prev := bindHook
	t.Cleanup(func() { bindHook = prev })
	bindHook = func(networkHandle uint64, fd uintptr) error {
		seenHandle.Store(networkHandle)
		seenFD.Store(uint64(fd))
		return nil
	}

	ln, err := (&net.ListenConfig{Control: Control(0xdeadbeefcafe)}).ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket failed: %v", err)
	}
	defer ln.Close()

	if got := seenHandle.Load(); got != 0xdeadbeefcafe {
		t.Fatalf("seenHandle = %#x, want 0xdeadbeefcafe", got)
	}
	if seenFD.Load() == 0 {
		t.Fatal("expected non-zero socket fd")
	}
}

func TestControlPropagatesBindError(t *testing.T) {
	prev := bindHook
	t.Cleanup(func() { bindHook = prev })
	bindHook = func(networkHandle uint64, fd uintptr) error {
		return errors.New("network gone")
	}

	_, err := (&net.ListenConfig{Control: Control(42)}).ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
	if err == nil {
		t.Fatal("expected ListenPacket to fail when bindHook errors")
	}
}

func TestLookupHostUsesHookWhenNetworkSpecified(t *testing.T) {
	prev := lookupHook
	t.Cleanup(func() { lookupHook = prev })
	lookupHook = func(ctx context.Context, networkHandle uint64, host string) ([]string, error) {
		if networkHandle != 777 || host != "router.lan" {
			t.Fatalf("unexpected lookup (%d, %q)", networkHandle, host)
		}
		return []string{"192.168.50.1"}, nil
	}

	addrs, err := LookupHost(context.Background(), 777, "router.lan")
	if err != nil {
		t.Fatalf("LookupHost failed: %v", err)
	}
	if len(addrs) != 1 || addrs[0] != "192.168.50.1" {
		t.Fatalf("addrs = %v, want [192.168.50.1]", addrs)
	}
}
