// Package tsbridge provides a minimal Go bridge over Tailscale's tsnet
// library, exposed via gomobile for Haven's per-app Tailscale support
// (#102 follow-up).
//
// Shares the public API shape with [wgbridge] — StartTunnel →
// TunnelHandle → Dial → Conn → Read/Write/Close — so the Kotlin side
// can treat both backends uniformly through [sh.haven.core.tunnel.Tunnel].
//
// Differences from wgbridge worth noting:
//   - Tailscale needs a writable state directory (control state, node
//     keys, cert cache). The caller passes an absolute path owned by the
//     app — typically context.filesDir/tailscale-<configId>/ — which
//     tsnet creates if missing.
//   - Auth is an authkey (tskey-auth-...) rather than a wg-quick config.
//     The authkey is used once to join the tailnet; subsequent starts
//     reuse the persisted state directly.
//   - MagicDNS works transparently because tsnet.Server.Dial routes
//     hostname lookups through the tailnet resolver. A dial to
//     "my-laptop.tailnet.ts.net:22" resolves + tunnels in one call.
package tsbridge

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
*/
import "C"

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"

	dns "golang.org/x/net/dns/dnsmessage"

	"sh.haven/rcbridge/socks5"
)

// init wires an Android-safe interface enumerator into tsnet's
// RegisterInterfaceGetter hook. Tailscale added this hook specifically
// for Android (SDK 30+) because Go stdlib's `net.Interfaces()` opens
// NETLINK_ROUTE sockets which `untrusted_app` SELinux contexts can't.
// Without this hook, tsnet.Up() bails with
// "netlinkrib: permission denied" before it can do anything else.
//
// Our implementation uses libc's `getifaddrs(3)` via cgo. On Android
// bionic (API 24+) this is implemented on top of `ioctl(SIOCGIFCONF)`
// on a UDP socket — a syscall path that `untrusted_app` IS permitted
// to use (the same path Java's `NetworkInterface.getNetworkInterfaces`
// uses). It gives us names + flags + IPv4/IPv6 addresses without ever
// touching netlink or `/proc/net`, both of which Android's SELinux
// policy blocks.
func init() {
	netmon.RegisterInterfaceGetter(androidSafeInterfaces)
}

func androidSafeInterfaces() ([]netmon.Interface, error) {
	// Try Go stdlib first — works on desktop Linux and older Androids
	// where netlink was still allowed. Falls through on EACCES.
	if ifs, err := net.Interfaces(); err == nil && len(ifs) > 0 {
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			out[i].Interface = &ifs[i]
			// Addrs() also uses netlink under the hood; fetch via
			// getifaddrs instead and stash them where tsnet looks.
			out[i].AltAddrs, _ = getifaddrsAddrs(ifs[i].Name)
		}
		return out, nil
	}
	return getifaddrsInterfaces()
}

// ifaceInfo is an intermediate struct we assemble from getifaddrs
// before converting to netmon.Interface. Grouped by interface name —
// getifaddrs returns one entry per (interface, address) pair and we
// need one netmon.Interface per interface.
type ifaceInfo struct {
	name  string
	index int
	flags net.Flags
	mtu   int
	addrs []net.Addr
}

// getifaddrsInterfaces enumerates network interfaces via libc's
// getifaddrs(3). SELinux-safe on Android untrusted_app since the
// underlying syscalls are ioctl(SIOCGIFCONF) + socket operations, not
// netlink.
func getifaddrsInterfaces() ([]netmon.Interface, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)

	byName := map[string]*ifaceInfo{}
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		name := C.GoString(ifa.ifa_name)
		info := byName[name]
		if info == nil {
			info = &ifaceInfo{
				name:  name,
				flags: translateFlags(uint32(ifa.ifa_flags)),
			}
			byName[name] = info
		}
		if addr := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); addr != nil {
			info.addrs = append(info.addrs, addr)
		}
	}

	out := make([]netmon.Interface, 0, len(byName))
	for _, info := range byName {
		ni := &net.Interface{
			Index: info.index,
			Name:  info.name,
			Flags: info.flags,
			MTU:   info.mtu,
		}
		out = append(out, netmon.Interface{
			Interface: ni,
			AltAddrs:  info.addrs,
		})
	}
	return out, nil
}

// getifaddrsAddrs returns just the addresses for a named interface, for
// the "Go stdlib net.Interfaces worked but Addrs() may not" code path.
func getifaddrsAddrs(name string) ([]net.Addr, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)
	var addrs []net.Addr
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		if C.GoString(ifa.ifa_name) != name {
			continue
		}
		if a := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); a != nil {
			addrs = append(addrs, a)
		}
	}
	return addrs, nil
}

// translateFlags maps Linux IFF_* flags to Go's net.Flags.
func translateFlags(f uint32) net.Flags {
	var out net.Flags
	if f&C.IFF_UP != 0 {
		out |= net.FlagUp
	}
	if f&C.IFF_BROADCAST != 0 {
		out |= net.FlagBroadcast
	}
	if f&C.IFF_LOOPBACK != 0 {
		out |= net.FlagLoopback
	}
	if f&C.IFF_POINTOPOINT != 0 {
		out |= net.FlagPointToPoint
	}
	if f&C.IFF_MULTICAST != 0 {
		out |= net.FlagMulticast
	}
	if f&C.IFF_RUNNING != 0 {
		out |= net.FlagRunning
	}
	return out
}

// sockaddrToAddr converts a C sockaddr (IPv4 or IPv6) to a net.IPNet
// carrying the address + prefix length. Returns nil for families we
// don't care about (AF_PACKET for MAC addresses, etc).
func sockaddrToAddr(sa, nm *C.struct_sockaddr) net.Addr {
	if sa == nil {
		return nil
	}
	switch sa.sa_family {
	case C.AF_INET:
		sin := (*C.struct_sockaddr_in)(unsafe.Pointer(sa))
		addr := (*[4]byte)(unsafe.Pointer(&sin.sin_addr))[:]
		ip := netip.AddrFrom4(*(*[4]byte)(addr))
		prefix := 32
		if nm != nil && nm.sa_family == C.AF_INET {
			mask := (*C.struct_sockaddr_in)(unsafe.Pointer(nm))
			maskBytes := (*[4]byte)(unsafe.Pointer(&mask.sin_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 32)}
	case C.AF_INET6:
		sin := (*C.struct_sockaddr_in6)(unsafe.Pointer(sa))
		addrBytes := (*[16]byte)(unsafe.Pointer(&sin.sin6_addr))[:]
		ip := netip.AddrFrom16(*(*[16]byte)(addrBytes))
		prefix := 128
		if nm != nil && nm.sa_family == C.AF_INET6 {
			mask := (*C.struct_sockaddr_in6)(unsafe.Pointer(nm))
			maskBytes := (*[16]byte)(unsafe.Pointer(&mask.sin6_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 128)}
	}
	return nil
}

// countLeadingOnes returns the prefix length of a netmask byte slice.
func countLeadingOnes(mask []byte) int {
	ones := 0
	for _, b := range mask {
		if b == 0xff {
			ones += 8
			continue
		}
		for b&0x80 != 0 {
			ones++
			b <<= 1
		}
		break
	}
	return ones
}

// TunnelHandle wraps a live tsnet.Server. Safe to Dial concurrently;
// Close is idempotent but not safe to race with Dial.
type TunnelHandle struct {
	srv     *tsnet.Server
	mu      sync.Mutex
	closed  bool
	socksLn net.Listener
}

// Conn is a TCP connection through the tunnel. Mirrors wgbridge.Conn so
// the Kotlin adapter can treat both the same way.
type Conn struct {
	c net.Conn
}

// RemoteAddrHost returns the literal IP this connection was actually
// established to — the resolved form of whatever name Dial was given
// (MagicDNS included). Empty string when unknown. Gomobile-friendly:
// a string, not a net.Addr, and never an unresolved name — callers use
// it where the contract demands an IP literal (#539: the mosh UDP
// destination fed to UDPConn.WriteTo).
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// UDPConn is an unconnected UDP socket on the tailnet. Mirrors
// wgbridge.UDPConn so the Kotlin TunneledDatagramSocket implementation
// can treat both backends identically.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result. See wgbridge.UDPRead.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// StartTunnel brings up a tailnet using the given authkey and state
// directory. hostname is advertised to the tailnet (shows up in the
// admin console); blank picks tsnet's default.
//
// controlURL points the client at a coordination server other than
// Tailscale's hosted controlplane.tailscale.com. Empty string keeps
// the default; non-empty is typically a self-hosted Headscale server
// (e.g. "https://headscale.example.com"). Tailscale's own control
// plane and Headscale share the same control protocol, so the same
// tsnet client speaks both — see #124, mcbalaam (Headscale users).
//
// Blocks until authenticated AND the peer map has been received, so a
// subsequent Dial to a MagicDNS name works first-try. Internal timeout
// is 60 s — first-run authkey consumption + control-plane handshake +
// peer-map sync can add up on a phone with flaky NAT. Returns errors
// carrying the underlying Tailscale diagnostic; common causes include
// expired/bad authkey, tagged-node ACL restrictions, coordination
// server unreachable.
//
// tsnet's internal logs route through Go's default logger which
// gomobile surfaces under logcat's "GoLog" tag, so a stuck handshake
// or DERP failure is diagnosable without a separate debug build.
func StartTunnel(authKey, stateDir, hostname, controlURL string) (*TunnelHandle, error) {
	if authKey == "" {
		return nil, errors.New("authkey required")
	}
	if stateDir == "" {
		return nil, errors.New("state directory required")
	}
	if hostname == "" {
		hostname = "haven-android"
	}
	srv := &tsnet.Server{
		AuthKey:    authKey,
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL, // empty = default controlplane.tailscale.com
		Ephemeral:  false,
		Logf:       tsnetLogf,
		UserLogf:   tsnetLogf,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet.Up: %w", err)
	}
	// Up() returns when login is accepted, but the peer-map sync runs
	// slightly after. Wait for self.Online or at least one peer before
	// returning — otherwise the first Dial races against MagicDNS and
	// times out.
	if err := waitForPeerMap(ctx, srv); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet wait-for-peers: %w", err)
	}
	return &TunnelHandle{srv: srv}, nil
}

// tsnetLogf pipes tsnet's internal log lines through Go's default
// logger, which gomobile routes to logcat as "GoLog".
func tsnetLogf(format string, args ...any) {
	fmt.Printf("[tsnet] "+format+"\n", args...)
}

// waitForPeerMap polls the local client until either the self node is
// marked online or at least one peer is present. A fresh tailnet join
// typically reports Self.Online first; a reconnect with cached peers
// may populate Peer first. Either signals that MagicDNS resolution
// should work on the next dial.
func waitForPeerMap(ctx context.Context, srv *tsnet.Server) error {
	lc, err := srv.LocalClient()
	if err != nil {
		return fmt.Errorf("LocalClient: %w", err)
	}
	var lastErr error
	for {
		st, err := lc.Status(ctx)
		if err == nil && st.Self != nil && (st.Self.Online || len(st.Peer) > 0) {
			return nil
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return fmt.Errorf("timeout (last status err=%v)", lastErr)
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// Dial opens a TCP connection through the tailnet. host may be a
// MagicDNS name (foo.tailnet.ts.net), a tailnet IP (100.x.y.z), or
// any IP that the tailnet can reach (e.g. a subnet-router hop).
// timeoutMs <= 0 means 30 s.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()

	// A console-configured global/split nameserver is only reachable
	// *through* the tailnet, but tsnet's own dial path resolves
	// non-MagicDNS names with Go's system resolver (the phone's DNS),
	// which can't reach it — so such names fail with "no such host"
	// before any dial happens (#691; upstream tailscale/tailscale#5840).
	// Resolve the name through the tailnet's own resolver first and dial
	// the resulting IP. On any miss (not a name, no tailnet resolver,
	// NXDOMAIN, timeout) fall back to the plain dial so MagicDNS names,
	// IPs and public hosts keep working exactly as before.
	if addrs, ok := resolveViaTailnet(ctx, srv, host); ok && len(addrs) > 0 {
		// A nameserver can return several A records of which only some are
		// reachable. Dial them in turn, each with a share of the remaining
		// budget, so a black-holed address can't consume the whole timeout
		// before a live one is reached — the behaviour Termux's SSH gets
		// from Go's stdlib dialer (#691 follow-up). Resolution succeeded
		// here, so on total failure we report it rather than falling back
		// to the plain dial (which would re-resolve via the phone's DNS and
		// fail with "no such host" for exactly the console-nameserver case).
		conn, err := dialFirstReachable(ctx, addrs, port, srv.Dial)
		if err != nil {
			return nil, fmt.Errorf("dial %s:%d via tailnet (tried %d address(es)): %w", host, port, len(addrs), err)
		}
		return conn, nil
	}

	c, err := srv.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d via tailnet: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// minAttemptDialTimeout is the floor for a single address's dial attempt in
// dialFirstReachable, so splitting the budget across many addresses can't
// leave a live one too little time to complete a TCP handshake.
const minAttemptDialTimeout = 2 * time.Second

// dialFirstReachable dials each address in turn and returns the first
// successful connection, or the last error when none connect. Each attempt
// gets the remaining ctx budget divided by the addresses still to try,
// floored at minAttemptDialTimeout, so an unreachable (black-holed) address
// is given up on in time to reach a later one. dial is injected so the
// ordering and budget logic is unit-testable without a live tailnet.
func dialFirstReachable(ctx context.Context, addrs []string, port int, dial func(ctx context.Context, network, addr string) (net.Conn, error)) (*Conn, error) {
	var lastErr error
	for i, addr := range addrs {
		attemptCtx := ctx
		if deadline, ok := ctx.Deadline(); ok {
			per := time.Until(deadline) / time.Duration(len(addrs)-i)
			if per < minAttemptDialTimeout {
				per = minAttemptDialTimeout
			}
			var cancel context.CancelFunc
			attemptCtx, cancel = context.WithTimeout(ctx, per)
			c, err := dial(attemptCtx, "tcp", net.JoinHostPort(addr, strconv.Itoa(port)))
			cancel()
			if err == nil {
				return &Conn{c: c}, nil
			}
			lastErr = err
		} else {
			c, err := dial(ctx, "tcp", net.JoinHostPort(addr, strconv.Itoa(port)))
			if err == nil {
				return &Conn{c: c}, nil
			}
			lastErr = err
		}
	}
	return nil, lastErr
}

// resolveViaTailnet resolves host through the tailnet's DNS resolver
// (which honours the tailnet's global/split nameserver config) and returns
// every A-record answer as bare IP strings, in the order the nameserver
// returned them. ok is false — meaning the caller should fall back to the
// plain dial — when host is already an IP, the resolver is unavailable, the
// query fails, or no A record comes back. MagicDNS names resolve here too
// (the resolver consults the netmap), so this is a superset of the old
// behaviour. All answers are returned, not just the first, so the caller can
// try each when only some are reachable (#691 follow-up).
func resolveViaTailnet(ctx context.Context, srv *tsnet.Server, host string) (ips []string, ok bool) {
	// This resolver path is less trodden than MagicDNS (which resolves via
	// the netmap, not here), so treat any panic — a malformed name reaching
	// MustNewName, a nil subsystem mid-teardown — as a miss and let the
	// caller fall back to the plain dial rather than crash it.
	defer func() {
		if r := recover(); r != nil {
			ips, ok = nil, false
		}
	}()
	if net.ParseIP(host) != nil {
		return nil, false
	}
	res := srv.Sys().DNSManager.Get().Resolver()
	if res == nil {
		return nil, false
	}
	qname := dns.MustNewName(host + ".")
	b := dns.NewBuilder(nil, dns.Header{RecursionDesired: true})
	b.StartQuestions()
	if err := b.Question(dns.Question{Name: qname, Type: dns.TypeA, Class: dns.ClassINET}); err != nil {
		return nil, false
	}
	query, err := b.Finish()
	if err != nil {
		return nil, false
	}
	// Give the resolver query its own short budget so a tailnet with no
	// reachable nameserver for this name can't eat the whole dial timeout
	// before we fall back to the plain dial. The caller's ctx still caps it.
	qctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	// The resolver keys off the query bytes, not `from`; a loopback
	// source is what upstream's own split-DNS test uses.
	from := netip.MustParseAddrPort("127.0.0.1:0")
	resp, err := res.Query(qctx, query, "udp", from)
	if err != nil {
		return nil, false
	}
	return parseAllA(resp)
}

// parseAllA extracts every A-record answer from a DNS response, in order.
// Returns ok=false when the response carries no A record (or fails to
// parse), so the caller falls back to the plain dial.
func parseAllA(resp []byte) ([]string, bool) {
	var p dns.Parser
	if _, err := p.Start(resp); err != nil {
		return nil, false
	}
	if err := p.SkipAllQuestions(); err != nil {
		return nil, false
	}
	var ips []string
	for {
		h, err := p.AnswerHeader()
		if err != nil {
			break // no more answers, or a parse error — keep what we have
		}
		if h.Type != dns.TypeA {
			continue
		}
		a, err := p.AResource()
		if err != nil {
			break // stop at a parse error, keep the records parsed so far
		}
		ips = append(ips, netip.AddrFrom4(a.A).String())
	}
	return ips, len(ips) > 0
}

// ListenUDP opens an unconnected UDP socket on the tailnet, bound to the
// node's own Tailscale IPv4 (or IPv6 if no v4 is assigned) on an
// ephemeral port. Packets sent through this socket traverse the tailnet
// rather than the device's default route — that's the entire point of
// #164 for Mosh-over-tailnet.
//
// Returns an error if the tunnel is closed or if Tailscale hasn't been
// assigned a usable IP yet (shouldn't happen post-[StartTunnel], which
// blocks on peer-map sync).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	ip4, ip6 := srv.TailscaleIPs()
	var bind string
	switch {
	case ip4.IsValid():
		bind = net.JoinHostPort(ip4.String(), "0")
	case ip6.IsValid():
		bind = net.JoinHostPort(ip6.String(), "0")
	default:
		return nil, errors.New("tailnet has not assigned a usable IP yet")
	}
	pc, err := srv.ListenPacket("udp", bind)
	if err != nil {
		return nil, fmt.Errorf("tsnet ListenPacket %s: %w", bind, err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting
// this tailnet and returns its bound TCP port. Idempotent; closing the
// tunnel tears the listener down. Mirrors wgbridge's equivalent — the
// same Kotlin caller can use either tunnel type.
func (t *TunnelHandle) StartSocksListener() (int, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return 0, errors.New("tunnel closed")
	}
	if t.socksLn != nil {
		port := t.socksLn.Addr().(*net.TCPAddr).Port
		t.mu.Unlock()
		return port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.mu.Unlock()
		return 0, fmt.Errorf("bind SOCKS5 listener: %w", err)
	}
	t.socksLn = ln
	srv := t.srv
	t.mu.Unlock()

	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return srv.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})

	return ln.Addr().(*net.TCPAddr).Port, nil
}

// Close tears down the tailnet connection. The state directory is kept
// intact so a subsequent StartTunnel picks up without re-auth.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	if t.socksLn != nil {
		t.socksLn.Close()
		t.socksLn = nil
	}
	if t.srv != nil {
		_ = t.srv.Close()
		t.srv = nil
	}
}

// Read returns up to size bytes. Signals EOF the same way wgbridge does
// (nil slice + io.EOF) so the Kotlin InputStream wrapper can treat both
// backends identically.
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}

// Write writes all of data. Gomobile copies []byte across JNI so the
// caller's array isn't mutated here.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// ReadFrom matches wgbridge.UDPConn.ReadFrom — same timeout semantics
// (timeoutMs <= 0 blocks; >0 sets a one-shot read deadline) and same
// gomobile-friendly return shape.
func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		_ = u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	} else {
		_ = u.c.SetReadDeadline(time.Time{})
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		return &UDPRead{Data: buf[:n], FromHost: addr.String(), FromPort: 0}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
}

// WriteTo sends data to host:port through the tailnet. host must be a
// literal IP (Mosh always knows the server IP via the SSH bootstrap's
// MOSH CONNECT line; MagicDNS resolution belongs in the SSH-bootstrap
// path, not the per-packet UDP send path).
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	addr := &net.UDPAddr{IP: ip, Port: port}
	_, err := u.c.WriteTo(data, addr)
	return err
}

// Close closes the UDP socket. Idempotent.
func (u *UDPConn) Close() error {
	return u.c.Close()
}
