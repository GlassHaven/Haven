package yggbridge

import (
	"net"
	"strings"
	"testing"
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