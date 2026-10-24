package ovpnbridge

import (
	"errors"
	"os"
	"sync"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun"
)

const testTimeout = 2 * time.Second

// fakeTUN is a hand-written tun.Device for deterministic unit tests of the
// pump's own relay logic. netstack.CreateNetTUN's tun.Device is not a
// loopback (Write = inbound-to-stack, Read = outbound-from-stack), so a real
// netstack TUN can't be used to assert "a packet written in comes back out
// unchanged" -- that was never true. This fake exposes both directions as
// plain channels instead.
type fakeTUN struct {
	writes chan []byte // packets the pump handed to dev.Write (native->tun direction)
	reads  chan []byte // packets the test injects for dev.Read to return (tun->native direction)

	mu     sync.Mutex
	closed bool
	done   chan struct{}
	events chan tun.Event
}

func newFakeTUN() *fakeTUN {
	return &fakeTUN{
		writes: make(chan []byte, 8),
		reads:  make(chan []byte, 8),
		done:   make(chan struct{}),
		events: make(chan tun.Event),
	}
}

func (f *fakeTUN) File() *os.File { return nil }

func (f *fakeTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case pkt := <-f.reads:
		n := copy(bufs[0][offset:], pkt)
		sizes[0] = n
		return 1, nil
	case <-f.done:
		return 0, errors.New("fakeTUN: closed")
	}
}

func (f *fakeTUN) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		pkt := make([]byte, len(b)-offset)
		copy(pkt, b[offset:])
		select {
		case f.writes <- pkt:
		case <-f.done:
			return 0, errors.New("fakeTUN: closed")
		}
	}
	return len(bufs), nil
}

func (f *fakeTUN) MTU() (int, error)        { return 1420, nil }
func (f *fakeTUN) Name() (string, error)    { return "fakeTUN", nil }
func (f *fakeTUN) Events() <-chan tun.Event { return f.events }
func (f *fakeTUN) BatchSize() int           { return 1 }

func (f *fakeTUN) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return nil
	}
	f.closed = true
	close(f.done)
	close(f.events)
	return nil
}

// socketpair returns the raw fd meant for newPacketPump (nativeFD, simulating
// the adapter.cpp side of the real socketpair) plus an *os.File peer the test
// drives directly. SOCK_DGRAM preserves datagram boundaries, matching the
// one-read-equals-one-packet framing the pump relies on.
func socketpair(t *testing.T) (nativeFD int, peer *os.File) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatalf("socketpair: %v", err)
	}
	// Non-blocking, same as newPacketPump does for nativeFD: os.File only
	// registers a fd with the runtime poller (required for SetReadDeadline)
	// when it sees the fd is already non-blocking at wrap time.
	if err := syscall.SetNonblock(fds[1], true); err != nil {
		t.Fatalf("SetNonblock(peer): %v", err)
	}
	peer = os.NewFile(uintptr(fds[1]), "test-peer")
	t.Cleanup(func() { peer.Close() })
	return fds[0], peer
}

func TestPump_NativeToTun(t *testing.T) {
	nativeFD, peer := socketpair(t)
	dev := newFakeTUN()
	pump, err := newPacketPump(nativeFD, dev)
	if err != nil {
		t.Fatalf("newPacketPump: %v", err)
	}
	defer pump.Close()

	want := []byte{0x45, 0x00, 0x00, 0x1c, 0xde, 0xad, 0xbe, 0xef}
	if _, err := peer.Write(want); err != nil {
		t.Fatalf("peer.Write: %v", err)
	}

	select {
	case got := <-dev.writes:
		if string(got) != string(want) {
			t.Fatalf("dev.Write got %x, want %x", got, want)
		}
	case <-time.After(testTimeout):
		t.Fatal("timed out waiting for packet to reach dev.Write")
	}
}

func TestPump_TunToNative(t *testing.T) {
	nativeFD, peer := socketpair(t)
	dev := newFakeTUN()
	pump, err := newPacketPump(nativeFD, dev)
	if err != nil {
		t.Fatalf("newPacketPump: %v", err)
	}
	defer pump.Close()

	want := []byte{0x60, 0x00, 0x00, 0x00, 0xca, 0xfe}
	select {
	case dev.reads <- want:
	case <-time.After(testTimeout):
		t.Fatal("timed out injecting packet into dev.Read")
	}

	buf := make([]byte, 2048)
	if err := peer.SetReadDeadline(time.Now().Add(testTimeout)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	n, err := peer.Read(buf)
	if err != nil {
		t.Fatalf("peer.Read: %v", err)
	}
	if string(buf[:n]) != string(want) {
		t.Fatalf("peer.Read got %x, want %x", buf[:n], want)
	}
}

func TestPump_CloseIdempotent(t *testing.T) {
	nativeFD, peer := socketpair(t)
	defer peer.Close()
	dev := newFakeTUN()
	pump, err := newPacketPump(nativeFD, dev)
	if err != nil {
		t.Fatalf("newPacketPump: %v", err)
	}

	done := make(chan error, 2)
	go func() { done <- pump.Close() }()
	go func() { done <- pump.Close() }()

	for i := 0; i < 2; i++ {
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("Close returned error: %v", err)
			}
		case <-time.After(testTimeout):
			t.Fatal("Close did not return within deadline")
		}
	}
}
