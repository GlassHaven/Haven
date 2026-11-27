package yggbridge

import (
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"sh.haven/rcbridge/socks5"
)

// TestStartTunnelGeneratesIdentity proves an empty config generates a usable
// mesh identity: a 200::/7 address, a public key, and a routed /64 subnet.
func TestStartTunnelGeneratesIdentity(t *testing.T) {
	h, err := StartTunnel("{}")
	if err != nil {
		t.Fatalf("StartTunnel: %v", err)
	}
	defer h.Close()

	addr := h.LocalAddress()
	ip := net.ParseIP(addr)
	if ip == nil {
		t.Fatalf("LocalAddress %q is not an IP literal", addr)
	}
	if ip[0]&0xfe != 0x02 {
		t.Fatalf("LocalAddress %q is not in the 200::/7 mesh range", addr)
	}
	if h.PublicKey() == "" {
		t.Fatal("PublicKey is empty")
	}
	if !strings.Contains(h.Subnet(), "/") {
		t.Fatalf("Subnet %q is not CIDR notation", h.Subnet())
	}
}

// TestLoopbackTCP exercises the netstack plumbing: a listener bound on the mesh
// address and a dial to it transfer bytes both ways (gVisor HandleLocal delivers
// self-addressed traffic inside the stack).
func TestLoopbackTCP(t *testing.T) {
	h, err := StartTunnel("{}")
	if err != nil {
		t.Fatalf("StartTunnel: %v", err)
	}
	defer h.Close()

	const port = 47101
	ln, err := h.ListenTCP(port)
	if err != nil {
		t.Fatalf("ListenTCP: %v", err)
	}
	defer ln.Close()

	payload := []byte("hello yggdrasil mesh")
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		buf, _ := c.Read(4096)
		_ = c.Write(buf)
	}()

	c, err := h.Dial(h.LocalAddress(), port, 5000)
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	defer c.Close()
	if err := c.Write(payload); err != nil {
		t.Fatalf("Write: %v", err)
	}
	got, err := c.Read(4096)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("echo = %q, want %q", got, payload)
	}
}

// TestCloseIdempotent proves Close is safe to repeat and that Dial afterwards
// fails cleanly rather than panicking.
func TestCloseIdempotent(t *testing.T) {
	h, err := StartTunnel("{}")
	if err != nil {
		t.Fatalf("StartTunnel: %v", err)
	}
	addr := h.LocalAddress()
	h.Close()
	h.Close()
	if _, err := h.Dial(addr, 47101, 1000); err == nil {
		t.Fatal("Dial after Close should return an error")
	}
}

// TestPeerOverCarrierSocks5 proves #876's carrier-SOCKS5 underlay path end to
// end: node A has no direct peer URI to node B, only a sockstls:// URI pointing
// at Haven's socks5 listener, and transfers TCP bytes across the encrypted
// 200::/7 mesh between two distinct nodes.
func TestPeerOverCarrierSocks5(t *testing.T) {
	probeLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve port: %v", err)
	}
	peerPort := probeLn.Addr().(*net.TCPAddr).Port
	_ = probeLn.Close()

	nodeB, err := StartTunnel(fmt.Sprintf(`{"Listen":["tls://127.0.0.1:%d"]}`, peerPort))
	if err != nil {
		t.Fatalf("StartTunnel nodeB: %v", err)
	}
	defer nodeB.Close()

	var proxiedDials atomic.Int32
	socksLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen socks5: %v", err)
	}
	defer socksLn.Close()
	socksPort := socksLn.Addr().(*net.TCPAddr).Port
	go socks5.Serve(socksLn, func(host string, port int) (net.Conn, error) {
		proxiedDials.Add(1)
		return net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 5*time.Second)
	})

	nodeAConfig := fmt.Sprintf(
		`{"Peers":["sockstls://127.0.0.1:%d/127.0.0.1:%d"]}`,
		socksPort, peerPort,
	)
	nodeA, err := StartTunnel(nodeAConfig)
	if err != nil {
		t.Fatalf("StartTunnel nodeA: %v", err)
	}
	defer nodeA.Close()

	const echoPort = 47202
	echoLn, err := nodeB.ListenTCP(echoPort)
	if err != nil {
		t.Fatalf("nodeB.ListenTCP: %v", err)
	}
	defer echoLn.Close()

	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func(conn *Conn) {
				defer conn.Close()
				buf, rerr := conn.Read(4096)
				if rerr == nil && len(buf) > 0 {
					_ = conn.Write(buf)
				}
			}(c)
		}
	}()

	payload := []byte("mesh-over-carrier-socks5")
	var lastErr error
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		c, derr := nodeA.Dial(nodeB.LocalAddress(), echoPort, 1500)
		if derr != nil {
			lastErr = derr
			time.Sleep(150 * time.Millisecond)
			continue
		}
		if werr := c.Write(payload); werr != nil {
			_ = c.Close()
			lastErr = werr
			time.Sleep(150 * time.Millisecond)
			continue
		}
		got, rerr := c.Read(4096)
		_ = c.Close()
		if rerr != nil {
			lastErr = rerr
			time.Sleep(150 * time.Millisecond)
			continue
		}
		if string(got) != string(payload) {
			t.Fatalf("mesh echo = %q, want %q", got, payload)
		}
		if proxiedDials.Load() < 1 {
			t.Fatal("expected at least 1 dial through carrier SOCKS5 listener, got 0")
		}
		return
	}
	t.Fatalf("mesh dial over carrier SOCKS5 did not succeed before deadline: %v (proxiedDials=%d)", lastErr, proxiedDials.Load())
}
