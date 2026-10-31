package ovpnbridge

import (
	"fmt"
	"net/netip"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/ovpnbridge/openvpn3"
)

// defaultMTU matches wgbridge's own choice (see wgbridge.go's defaultMTU
// comment): 1280 is the IPv6 minimum link MTU and leaves headroom on
// constrained paths (mobile/4G, CGNAT, a nested tunnel) that would silently
// stall sustained TCP at full-size 1500-byte packets.
const defaultMTU = 1280

// establishResult is sent on OvpnTunnelSetup.ready exactly once, whichever
// happens first: a successful Establish() with a live netstack, or a fatal
// OnEvent (e.g. AUTH_FAILED) fired before OpenVPN3 core ever gets to call
// Establish() at all.
type establishResult struct {
	tnet     *netstack.Net
	bindAddr netip.Addr
	err      error
}

// OvpnTunnelSetup implements openvpn3.TunnelSetup, translating OpenVPN3
// Core's tun_builder_* callback sequence into a gVisor netstack tunnel
// fronted by a socketpair + packetPump (pump.go). OpenVPN3 core aborts tun
// setup if ANY invoked builder callback returns false, so every method below
// is overridden with a real value (Persist is the one deliberate false —
// see adapter.cpp's comment on why), never left to a noop default.
type OvpnTunnelSetup struct {
	mu        sync.Mutex
	addresses []netip.Addr
	dns       []netip.Addr
	mtu       int
	pump      *packetPump
	tnet      *netstack.Net
	bindAddr  netip.Addr

	establishOnce sync.Once
	ready         chan establishResult
}

var _ openvpn3.TunnelSetup = (*OvpnTunnelSetup)(nil)

func newOvpnTunnelSetup() *OvpnTunnelSetup {
	return &OvpnTunnelSetup{mtu: defaultMTU, ready: make(chan establishResult, 1)}
}

// notifyReady is safe to call more than once (reconnect, a late failure
// event after Establish() already succeeded, ...) — only the first call is
// ever delivered, matching a synchronous StartTunnel that already returned.
func (s *OvpnTunnelSetup) notifyReady(tnet *netstack.Net, bindAddr netip.Addr, err error) {
	s.establishOnce.Do(func() {
		s.ready <- establishResult{tnet: tnet, bindAddr: bindAddr, err: err}
	})
}

// currentNet returns the active netstack and bind address installed by the
// most recent Establish() call.
func (s *OvpnTunnelSetup) currentNet() (*netstack.Net, netip.Addr) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.tnet, s.bindAddr
}

func (s *OvpnTunnelSetup) NewBuilder() bool {
	s.mu.Lock()
	s.addresses = nil
	s.dns = nil
	s.mtu = defaultMTU
	s.mu.Unlock()
	return true
}

func (s *OvpnTunnelSetup) SetLayer(layer int) bool { return true }

func (s *OvpnTunnelSetup) SetRemoteAddress(ipAddress string, ipv6 bool) bool { return true }

func (s *OvpnTunnelSetup) AddAddress(address string, prefixLength int, gateway string, ipv6 bool, net30 bool) bool {
	addr, err := netip.ParseAddr(address)
	if err != nil {
		return false
	}
	s.mu.Lock()
	s.addresses = append(s.addresses, addr)
	s.mu.Unlock()
	return true
}

func (s *OvpnTunnelSetup) SetRouteMetricDefault(metric int) bool { return true }

func (s *OvpnTunnelSetup) RerouteGw(ipv4 bool, ipv6 bool, flags int) bool { return true }

func (s *OvpnTunnelSetup) AddRoute(address string, prefixLength int, metric int, ipv6 bool) bool {
	return true
}

func (s *OvpnTunnelSetup) ExcludeRoute(address string, prefixLength int, metric int, ipv6 bool) bool {
	return true
}

func (s *OvpnTunnelSetup) AddDnsServer(address string, ipv6 bool) bool {
	if addr, err := netip.ParseAddr(address); err == nil {
		s.mu.Lock()
		s.dns = append(s.dns, addr)
		s.mu.Unlock()
	}
	return true
}

func (s *OvpnTunnelSetup) AddSearchDomain(domain string) bool { return true }

func (s *OvpnTunnelSetup) SetMtu(mtu int) bool {
	if mtu > 0 {
		s.mu.Lock()
		s.mtu = mtu
		s.mu.Unlock()
	}
	return true
}

func (s *OvpnTunnelSetup) SetSessionName(name string) bool { return true }

func (s *OvpnTunnelSetup) AddProxyBypass(bypassHost string) bool { return true }

func (s *OvpnTunnelSetup) SetProxyAutoConfigUrl(url string) bool { return true }

func (s *OvpnTunnelSetup) SetProxyHttp(host string, port int) bool { return true }

func (s *OvpnTunnelSetup) SetProxyHttps(host string, port int) bool { return true }

func (s *OvpnTunnelSetup) AddWinsServer(address string) bool { return true }

func (s *OvpnTunnelSetup) SetBlockIpv6(ipv6Block bool) bool { return true }

func (s *OvpnTunnelSetup) SetAdapterDomainSuffix(name string) bool { return true }

func (s *OvpnTunnelSetup) Persist() bool { return false }

func (s *OvpnTunnelSetup) EstablishLite() {}

func (s *OvpnTunnelSetup) SocketProtect(socket int) bool { return true }

// Establish creates the netstack + socketpair + packetPump and returns the
// native-side fd for OpenVPN3 core to read/write raw IP packets on (core
// takes ownership of that fd from here, the same way it would own a real
// VpnService-provided tun fd — Go never closes it itself).
//
// OpenVPN3 may call this more than once: reconnect is normal OpenVPN
// behavior, not rare. Close any previous pump first so a second Establish()
// never leaves two pumps racing on the same gVisor netstack device.
func (s *OvpnTunnelSetup) Establish() (int, error) {
	s.mu.Lock()
	addresses := append([]netip.Addr(nil), s.addresses...)
	dns := append([]netip.Addr(nil), s.dns...)
	mtu := s.mtu
	prevPump := s.pump
	s.pump = nil
	s.mu.Unlock()

	if prevPump != nil {
		prevPump.Close()
	}

	if len(addresses) == 0 {
		err := fmt.Errorf("establish: no address pushed by server")
		s.notifyReady(nil, netip.Addr{}, err)
		return -1, err
	}

	dev, tnet, err := netstack.CreateNetTUN(addresses, dns, mtu)
	if err != nil {
		err = fmt.Errorf("create netstack TUN: %w", err)
		s.notifyReady(nil, netip.Addr{}, err)
		return -1, err
	}

	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		dev.Close()
		err = fmt.Errorf("socketpair: %w", err)
		s.notifyReady(nil, netip.Addr{}, err)
		return -1, err
	}

	pump, err := newPacketPump(fds[0], dev)
	if err != nil {
		dev.Close()
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
		err = fmt.Errorf("start packet pump: %w", err)
		s.notifyReady(nil, netip.Addr{}, err)
		return -1, err
	}

	// Non-blocking to match how a real tun fd behaves under OpenVPN3 core's
	// own asio event loop, which never expects a blocking fd.
	if err := syscall.SetNonblock(fds[1], true); err != nil {
		pump.Close()
		_ = unix.Close(fds[1])
		err = fmt.Errorf("set nonblocking (native fd): %w", err)
		s.notifyReady(nil, netip.Addr{}, err)
		return -1, err
	}

	// Prefer the first IPv4 address for the tunnel's bind/local address,
	// same convention as wgbridge.StartTunnel's bindAddr selection.
	var bindAddr netip.Addr
	for _, a := range addresses {
		if a.Is4() {
			bindAddr = a
			break
		}
	}
	if !bindAddr.IsValid() {
		bindAddr = addresses[0]
	}

	s.mu.Lock()
	s.pump = pump
	s.tnet = tnet
	s.bindAddr = bindAddr
	s.mu.Unlock()

	s.notifyReady(tnet, bindAddr, nil)
	return fds[1], nil
}

// Teardown closes the current pump, if any. Shares the cleanup path with
// Establish()'s own "close any previous pump" step.
func (s *OvpnTunnelSetup) Teardown(disconnect bool) {
	s.mu.Lock()
	pump := s.pump
	s.pump = nil
	s.mu.Unlock()
	if pump != nil {
		pump.Close()
	}
}
