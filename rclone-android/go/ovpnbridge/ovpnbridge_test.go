package ovpnbridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/ovpnbridge/openvpn3"
)

const fakeTunCSource = `#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int fake_tun_fd = -1;

static int open_fake_tun(void) {
    const char *env = getenv("OVPN_FAKE_TUN_FD");
    if (!env) {
        errno = EPERM;
        return -1;
    }
    int fd = dup(atoi(env));
    if (fd >= 0) {
        fake_tun_fd = fd;
    }
    return fd;
}

int open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (pathname && strcmp(pathname, "/dev/net/tun") == 0 && getenv("OVPN_FAKE_TUN_FD")) {
        return open_fake_tun();
    }
    int (*real_open)(const char *, int, mode_t) = dlsym(RTLD_NEXT, "open");
    return real_open(pathname, flags, mode);
}

int open64(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (pathname && strcmp(pathname, "/dev/net/tun") == 0 && getenv("OVPN_FAKE_TUN_FD")) {
        return open_fake_tun();
    }
    int (*real_open64)(const char *, int, mode_t) = dlsym(RTLD_NEXT, "open64");
    return real_open64(pathname, flags, mode);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (pathname && strcmp(pathname, "/dev/net/tun") == 0 && getenv("OVPN_FAKE_TUN_FD")) {
        return open_fake_tun();
    }
    int (*real_openat)(int, const char *, int, mode_t) = dlsym(RTLD_NEXT, "openat");
    return real_openat(dirfd, pathname, flags, mode);
}

int ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (fd == fake_tun_fd && fake_tun_fd >= 0) {
        if (request == TUNSETIFF && arg != NULL) {
            struct ifreq *ifr = (struct ifreq *)arg;
            if (ifr->ifr_name[0] == '\0' || strchr(ifr->ifr_name, '%') != NULL) {
                strncpy(ifr->ifr_name, "tun0", IFNAMSIZ - 1);
                ifr->ifr_name[IFNAMSIZ - 1] = '\0';
            }
        }
        return 0;
    }
    int (*real_ioctl)(int, unsigned long, void *) = dlsym(RTLD_NEXT, "ioctl");
    return real_ioctl(fd, request, arg);
}
`

func buildFakeTunSO(t *testing.T, dir string) string {
	t.Helper()
	cPath := filepath.Join(dir, "faketun.c")
	soPath := filepath.Join(dir, "libfaketun.so")
	if err := os.WriteFile(cPath, []byte(fakeTunCSource), 0o600); err != nil {
		t.Fatalf("write faketun.c: %v", err)
	}
	cmd := exec.Command("gcc", "-shared", "-fPIC", "-O2", "-o", soPath, cPath, "-ldl")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("cannot compile libfaketun.so (%v: %s)", err, out)
	}
	return soPath
}

// generateTestPKI creates an in-memory CA + server + client certificate chain
// in dir using crypto/x509 (no external easyrsa package required) plus an
// OpenVPN tls-auth key via `openvpn --genkey secret`.
func generateTestPKI(t *testing.T, dir string) (caCert, serverCert, serverKey, clientCert, clientKey, taKey string) {
	t.Helper()

	writePEM := func(name, typ string, der []byte) string {
		p := filepath.Join(dir, name)
		f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
		if err != nil {
			t.Fatalf("create %s: %v", p, err)
		}
		if err := pem.Encode(f, &pem.Block{Type: typ, Bytes: der}); err != nil {
			f.Close()
			t.Fatalf("encode %s: %v", p, err)
		}
		f.Close()
		return p
	}

	now := time.Now().Add(-time.Hour)
	caPriv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey(CA): %v", err)
	}
	caTmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "haven-test-ca"},
		NotBefore:             now,
		NotAfter:              now.Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTmpl, caTmpl, &caPriv.PublicKey, caPriv)
	if err != nil {
		t.Fatalf("CreateCertificate(CA): %v", err)
	}
	caParsed, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatalf("ParseCertificate(CA): %v", err)
	}
	caCert = writePEM("ca.crt", "CERTIFICATE", caDER)

	issueLeaf := func(serial int64, cn string, ku x509.KeyUsage, eku []x509.ExtKeyUsage) (certPath, keyPath string) {
		priv, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			t.Fatalf("GenerateKey(%s): %v", cn, err)
		}
		tmpl := &x509.Certificate{
			SerialNumber: big.NewInt(serial),
			Subject:      pkix.Name{CommonName: cn},
			NotBefore:    now,
			NotAfter:     now.Add(24 * time.Hour),
			KeyUsage:     ku,
			ExtKeyUsage:  eku,
		}
		der, err := x509.CreateCertificate(rand.Reader, tmpl, caParsed, &priv.PublicKey, caPriv)
		if err != nil {
			t.Fatalf("CreateCertificate(%s): %v", cn, err)
		}
		return writePEM(cn+".crt", "CERTIFICATE", der),
			writePEM(cn+".key", "RSA PRIVATE KEY", x509.MarshalPKCS1PrivateKey(priv))
	}

	serverCert, serverKey = issueLeaf(
		2, "server",
		x509.KeyUsageDigitalSignature|x509.KeyUsageKeyEncipherment,
		[]x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	)
	clientCert, clientKey = issueLeaf(
		3, "client",
		x509.KeyUsageDigitalSignature,
		[]x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	)

	taKeyPath := filepath.Join(dir, "ta.key")
	if out, err := exec.Command("openvpn", "--genkey", "secret", taKeyPath).CombinedOutput(); err != nil {
		t.Fatalf("openvpn --genkey secret: %v\n%s", err, out)
	}
	return caCert, serverCert, serverKey, clientCert, clientKey, taKeyPath
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(data)
}

// TestStartTunnel_RealHandshake generates a throwaway PKI, starts a real
// local `openvpn` server process backed by an unprivileged LD_PRELOAD
// socketpair TUN + gVisor netstack (no root or CAP_NET_ADMIN required), and
// drives a real OpenVPN3 Core session against it via StartTunnel -- proving
// Establish() actually ran end to end (real TLS handshake, real PUSH_REPLY
// parsed for the tunnel address + DNS, and real TCP/UDP traffic over the
// encrypted OpenVPN data channel).
func TestStartTunnel_RealHandshake(t *testing.T) {
	if _, err := exec.LookPath("openvpn"); err != nil {
		t.Skip("openvpn binary not found on PATH")
	}

	dir := t.TempDir()
	fakeTunSO := buildFakeTunSO(t, dir)
	caCert, serverCert, serverKey, clientCert, clientKey, taKey := generateTestPKI(t, dir)

	// Create a SOCK_DGRAM socketpair for the server side: fds[0] feeds our
	// simulated server gVisor netstack (10.77.0.1 + DNS 10.77.0.53); fds[1]
	// is handed to the openvpn server process as its /dev/net/tun fd.
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatalf("socketpair: %v", err)
	}
	if err := syscall.SetNonblock(fds[1], true); err != nil {
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
		t.Fatalf("SetNonblock: %v", err)
	}
	serverTunFile := os.NewFile(uintptr(fds[1]), "ovpn-server-tun")
	defer serverTunFile.Close()

	snet, closePeer := attachPeerNetstack(t, fds[0], "10.77.0.1", "10.77.0.53")
	defer closePeer()
	serveStaticDNS(t, snet, "10.77.0.53", "10.77.0.1")
	startTCPEcho(t, snet, "10.77.0.1:7007")
	startUDPEcho(t, snet, "10.77.0.1:8008")

	serverConf := filepath.Join(dir, "server.ovpn")
	serverConfBody := fmt.Sprintf(`port 11194
proto udp4
dev tun
disable-dco
ifconfig-noexec
route-noexec
ca %s
cert %s
key %s
dh none
tls-auth %s 0
topology subnet
server 10.77.0.0 255.255.255.0
push "dhcp-option DNS 10.77.0.53"
keepalive 10 60
persist-key
persist-tun
verb 3
`, caCert, serverCert, serverKey, taKey)
	if err := os.WriteFile(serverConf, []byte(serverConfBody), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	serverCmd := exec.Command("openvpn", "--config", serverConf)
	serverCmd.ExtraFiles = []*os.File{serverTunFile} // child fd 3
	serverCmd.Env = append(os.Environ(),
		"LD_PRELOAD="+fakeTunSO,
		"OVPN_FAKE_TUN_FD=3",
	)
	serverOut, err := serverCmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe: %v", err)
	}
	serverCmd.Stderr = serverCmd.Stdout
	if err := serverCmd.Start(); err != nil {
		t.Fatalf("start openvpn server: %v", err)
	}
	t.Cleanup(func() {
		_ = serverCmd.Process.Kill()
		_ = serverCmd.Wait()
	})

	ready := make(chan struct{}, 1)
	failed := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(serverOut)
		var tail []string
		for scanner.Scan() {
			line := scanner.Text()
			tail = append(tail, line)
			if len(tail) > 40 {
				tail = tail[1:]
			}
			if strings.Contains(line, "Initialization Sequence Completed") {
				select {
				case ready <- struct{}{}:
				default:
				}
				continue
			}
		}
		failed <- strings.Join(tail, "\n")
	}()

	select {
	case <-ready:
	case tail := <-failed:
		t.Fatalf("openvpn server exited before becoming ready:\n%s", tail)
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for openvpn server to become ready")
	}

	configText := fmt.Sprintf(`client
dev tun
proto udp4
remote 127.0.0.1 11194
nobind
persist-key
persist-tun
remote-cert-tls server
<ca>
%s
</ca>
<cert>
%s
</cert>
<key>
%s
</key>
<tls-auth>
%s
</tls-auth>
key-direction 1
verb 3
`, readFile(t, caCert), readFile(t, clientCert), readFile(t, clientKey), readFile(t, taKey))

	handle, err := StartTunnel(configText, "", "")
	if err != nil {
		t.Fatalf("StartTunnel: %v", err)
	}
	defer handle.Close()

	bindAddr := handle.BindAddr()
	if !strings.HasPrefix(bindAddr, "10.77.0.") {
		t.Fatalf("BindAddr() = %q, want an address in the server's 10.77.0.0/24 pool", bindAddr)
	}

	// Verify real encrypted data-channel traffic + pushed DNS resolution:
	conn, err := handle.Dial("echo.vpn.internal", 7007, 5000)
	if err != nil {
		t.Fatalf("Dial(echo.vpn.internal) over real OpenVPN tunnel: %v", err)
	}
	defer conn.Close()
	if err := conn.Write([]byte("real-openvpn3-handshake")); err != nil {
		t.Fatalf("conn.Write: %v", err)
	}
	got, err := conn.Read(64)
	if err != nil || string(got) != "real-openvpn3-handshake" {
		t.Fatalf("conn.Read = %q, %v; want real-openvpn3-handshake", got, err)
	}

	// Verify UDP over the real OpenVPN tunnel:
	udp, err := handle.ListenUDP()
	if err != nil {
		t.Fatalf("ListenUDP: %v", err)
	}
	defer udp.Close()
	if err := udp.WriteTo([]byte("real-mosh-udp"), "10.77.0.1", 8008); err != nil {
		t.Fatalf("udp.WriteTo: %v", err)
	}
	pkt, err := udp.ReadFrom(2048, 5000)
	if err != nil || string(pkt.Data) != "real-mosh-udp" {
		t.Fatalf("udp.ReadFrom = %+v, %v; want real-mosh-udp", pkt, err)
	}
}

// attachPeerNetstack creates a simulated server-side gVisor netstack with
// the given addresses and bridges it to the native socketpair fd returned by
// OvpnTunnelSetup.Establish(). Requires zero privileges (no /dev/net/tun).
func attachPeerNetstack(t *testing.T, nativeFD int, addrs ...string) (*netstack.Net, func()) {
	t.Helper()
	parsed := make([]netip.Addr, len(addrs))
	for i, a := range addrs {
		parsed[i] = netip.MustParseAddr(a)
	}
	dev, snet, err := netstack.CreateNetTUN(parsed, nil, defaultMTU)
	if err != nil {
		_ = unix.Close(nativeFD)
		t.Fatalf("CreateNetTUN(peer): %v", err)
	}
	pump, err := newPacketPump(nativeFD, dev)
	if err != nil {
		dev.Close()
		_ = unix.Close(nativeFD)
		t.Fatalf("newPacketPump(peer): %v", err)
	}
	return snet, func() { _ = pump.Close() }
}

// serveStaticDNS answers A and AAAA queries on snet at dnsIP:53, mapping any
// question name to targetIP (for A) and NXDOMAIN/empty (for AAAA).
func serveStaticDNS(t *testing.T, snet *netstack.Net, dnsIP, targetIP string) {
	t.Helper()
	pc, err := snet.ListenUDPAddrPort(netip.MustParseAddrPort(dnsIP + ":53"))
	if err != nil {
		t.Fatalf("ListenUDP(dns): %v", err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	target4 := netip.MustParseAddr(targetIP).As4()

	go func() {
		buf := make([]byte, 512)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil || n < 12 {
				return
			}
			req := buf[:n]
			// Find the end of the QNAME + QTYPE(2) + QCLASS(2).
			pos := 12
			for pos < len(req) && req[pos] != 0 {
				pos += 1 + int(req[pos])
			}
			if pos+5 > len(req) {
				continue
			}
			qtype := binary.BigEndian.Uint16(req[pos+1 : pos+3])
			qend := pos + 5

			resp := make([]byte, 0, qend+16)
			resp = append(resp, req[:2]...) // ID
			if qtype == 1 {                 // A record
				resp = append(resp, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
				resp = append(resp, req[12:qend]...)
				resp = append(resp, 0xc0, 0x0c)             // pointer to QNAME
				resp = append(resp, 0x00, 0x01, 0x00, 0x01) // TYPE A, CLASS IN
				resp = append(resp, 0x00, 0x00, 0x00, 0x3c) // TTL 60s
				resp = append(resp, 0x00, 0x04)             // RDLENGTH 4
				resp = append(resp, target4[:]...)
			} else { // empty NOERROR for AAAA etc.
				resp = append(resp, 0x81, 0x80, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
				resp = append(resp, req[12:qend]...)
			}
			_, _ = pc.WriteTo(resp, from)
		}
	}()
}

func startTCPEcho(t *testing.T, snet *netstack.Net, addrPort string) {
	t.Helper()
	ln, err := snet.ListenTCPAddrPort(netip.MustParseAddrPort(addrPort))
	if err != nil {
		t.Fatalf("ListenTCP(%s): %v", addrPort, err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				_, _ = io.Copy(c, c)
				_ = c.Close()
			}()
		}
	}()
}

func startUDPEcho(t *testing.T, snet *netstack.Net, addrPort string) {
	t.Helper()
	pc, err := snet.ListenUDPAddrPort(netip.MustParseAddrPort(addrPort))
	if err != nil {
		t.Fatalf("ListenUDP(%s): %v", addrPort, err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := string(buf[:n])
			if msg == "trigger-inbound:9009" {
				if ua, ok := from.(*net.UDPAddr); ok {
					target := net.JoinHostPort(ua.IP.String(), "9009")
					go func(dst string) {
						ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
						defer cancel()
						c, err := snet.DialContext(ctx, "tcp", dst)
						if err == nil {
							_, _ = c.Write([]byte("inbound-from-server"))
							_ = c.Close()
						}
					}(target)
				}
				continue
			}
			_, _ = pc.WriteTo(buf[:n], from)
		}
	}()
}

// TestServeDeviceOpenVPN starts the unprivileged OpenVPN test server on
// 0.0.0.0:11195 when HAVEN_OVPN_DEVICE_PROFILE_OUT is set, writes the client
// .ovpn profile (pointing at HAVEN_OVPN_DEVICE_HOST) to that path, and blocks
// until the test process is terminated or 90s elapses.
func TestServeDeviceOpenVPN(t *testing.T) {
	profileOut := os.Getenv("HAVEN_OVPN_DEVICE_PROFILE_OUT")
	if profileOut == "" {
		t.Skip("HAVEN_OVPN_DEVICE_PROFILE_OUT not set")
	}
	hostIP := os.Getenv("HAVEN_OVPN_DEVICE_HOST")
	if hostIP == "" {
		hostIP = "192.168.0.180"
	}

	dir := t.TempDir()
	fakeTunSO := buildFakeTunSO(t, dir)
	caCert, serverCert, serverKey, clientCert, clientKey, taKey := generateTestPKI(t, dir)

	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatalf("socketpair: %v", err)
	}
	if err := syscall.SetNonblock(fds[1], true); err != nil {
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
		t.Fatalf("SetNonblock: %v", err)
	}
	serverTunFile := os.NewFile(uintptr(fds[1]), "ovpn-device-server-tun")
	defer serverTunFile.Close()

	snet, closePeer := attachPeerNetstack(t, fds[0], "10.77.0.1", "10.77.0.53")
	defer closePeer()
	serveStaticDNS(t, snet, "10.77.0.53", "10.77.0.1")
	startTCPEcho(t, snet, "10.77.0.1:7007")
	startUDPEcho(t, snet, "10.77.0.1:8008")

	serverConf := filepath.Join(dir, "server.ovpn")
	serverConfBody := fmt.Sprintf(`port 11195
proto udp4
dev tun
disable-dco
ifconfig-noexec
route-noexec
ca %s
cert %s
key %s
dh none
tls-auth %s 0
topology subnet
server 10.77.0.0 255.255.255.0
push "dhcp-option DNS 10.77.0.53"
keepalive 10 60
persist-key
persist-tun
verb 3
`, caCert, serverCert, serverKey, taKey)
	if err := os.WriteFile(serverConf, []byte(serverConfBody), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	serverCmd := exec.Command("openvpn", "--config", serverConf)
	serverCmd.ExtraFiles = []*os.File{serverTunFile}
	serverCmd.Env = append(os.Environ(),
		"LD_PRELOAD="+fakeTunSO,
		"OVPN_FAKE_TUN_FD=3",
	)
	serverOut, err := serverCmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe: %v", err)
	}
	serverCmd.Stderr = serverCmd.Stdout
	if err := serverCmd.Start(); err != nil {
		t.Fatalf("start openvpn server: %v", err)
	}
	t.Cleanup(func() {
		_ = serverCmd.Process.Kill()
		_ = serverCmd.Wait()
	})

	ready := make(chan struct{}, 1)
	go func() {
		scanner := bufio.NewScanner(serverOut)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.Contains(line, "Initialization Sequence Completed") {
				select {
				case ready <- struct{}{}:
				default:
				}
			}
		}
	}()

	select {
	case <-ready:
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for openvpn device server")
	}

	configText := fmt.Sprintf(`client
dev tun
proto udp4
remote %s 11195
nobind
persist-key
persist-tun
remote-cert-tls server
<ca>
%s
</ca>
<cert>
%s
</cert>
<key>
%s
</key>
<tls-auth>
%s
</tls-auth>
key-direction 1
verb 3
`, hostIP, readFile(t, caCert), readFile(t, clientCert), readFile(t, clientKey), readFile(t, taKey))

	if err := os.WriteFile(profileOut, []byte(configText), 0o600); err != nil {
		t.Fatalf("write profileOut: %v", err)
	}
	t.Logf("READY: OpenVPN server listening on %s:11195, profile written to %s", hostIP, profileOut)
	time.Sleep(90 * time.Second)
}

func TestOvpnTunnelSetup_NetstackTCPUDPAndDNS(t *testing.T) {
	setup := newOvpnTunnelSetup()
	setup.NewBuilder()
	setup.AddAddress("10.77.0.2", 24, "10.77.0.1", false, false)
	setup.AddDnsServer("10.77.0.53", false)
	setup.SetMtu(1280)

	nativeFD, err := setup.Establish()
	if err != nil {
		t.Fatalf("Establish: %v", err)
	}
	snet, closePeer := attachPeerNetstack(t, nativeFD, "10.77.0.1", "10.77.0.53")
	defer closePeer()

	serveStaticDNS(t, snet, "10.77.0.53", "10.77.0.1")
	startTCPEcho(t, snet, "10.77.0.1:7007")
	startUDPEcho(t, snet, "10.77.0.1:8008")

	res := <-setup.ready
	if res.err != nil {
		t.Fatalf("ready err: %v", res.err)
	}
	handle := &TunnelHandle{
		setup:    setup,
		tnet:     res.tnet,
		bindAddr: res.bindAddr,
	}
	defer handle.Close()

	// 1. Dial by hostname resolved through the pushed DNS server (10.77.0.53).
	conn, err := handle.Dial("echo.vpn.internal", 7007, 3000)
	if err != nil {
		t.Fatalf("Dial(echo.vpn.internal): %v", err)
	}
	defer conn.Close()
	if got := conn.RemoteAddrHost(); got != "10.77.0.1" {
		t.Fatalf("RemoteAddrHost() = %q, want 10.77.0.1", got)
	}
	if err := conn.Write([]byte("hello-ovpn")); err != nil {
		t.Fatalf("conn.Write: %v", err)
	}
	got, err := conn.Read(64)
	if err != nil || string(got) != "hello-ovpn" {
		t.Fatalf("conn.Read = %q, %v; want %q", got, err, "hello-ovpn")
	}

	// 2. ListenUDP (used by Mosh).
	udp, err := handle.ListenUDP()
	if err != nil {
		t.Fatalf("ListenUDP: %v", err)
	}
	defer udp.Close()
	if err := udp.WriteTo([]byte("mosh-datagram"), "10.77.0.1", 8008); err != nil {
		t.Fatalf("udp.WriteTo: %v", err)
	}
	pkt, err := udp.ReadFrom(2048, 3000)
	if err != nil {
		t.Fatalf("udp.ReadFrom: %v", err)
	}
	if string(pkt.Data) != "mosh-datagram" || pkt.FromHost != "10.77.0.1" || pkt.FromPort != 8008 {
		t.Fatalf("udp.ReadFrom = %+v, want mosh-datagram from 10.77.0.1:8008", pkt)
	}

	// 3. ListenTCP (used by reverse tunnels / MCP endpoint).
	ln, err := handle.ListenTCP(9009)
	if err != nil {
		t.Fatalf("ListenTCP: %v", err)
	}
	defer ln.Close()
	accepted := make(chan *Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err == nil {
			accepted <- c
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	peerConn, err := snet.DialContext(ctx, "tcp", "10.77.0.2:9009")
	if err != nil {
		t.Fatalf("peer DialContext to ListenTCP: %v", err)
	}
	defer peerConn.Close()
	srvConn := <-accepted
	defer srvConn.Close()
	if err := srvConn.Write([]byte("inbound-ok")); err != nil {
		t.Fatalf("srvConn.Write: %v", err)
	}
	buf := make([]byte, 10)
	if _, err := io.ReadFull(peerConn, buf); err != nil || string(buf) != "inbound-ok" {
		t.Fatalf("peerConn read = %q, %v; want inbound-ok", buf, err)
	}
}

func TestOvpnTunnelSetup_ReconnectSwapsNetstackAndResetsBuilder(t *testing.T) {
	setup := newOvpnTunnelSetup()
	setup.NewBuilder()
	setup.AddAddress("10.77.0.2", 24, "10.77.0.1", false, false)
	nativeFD1, err := setup.Establish()
	if err != nil {
		t.Fatalf("Establish #1: %v", err)
	}
	_, closePeer1 := attachPeerNetstack(t, nativeFD1, "10.77.0.1")
	defer closePeer1()

	res := <-setup.ready
	handle := &TunnelHandle{
		setup:    setup,
		tnet:     res.tnet,
		bindAddr: res.bindAddr,
	}
	defer handle.Close()

	if got := handle.BindAddr(); got != "10.77.0.2" {
		t.Fatalf("initial BindAddr() = %q, want 10.77.0.2", got)
	}

	// Simulate an OpenVPN3 Core reconnect with a new pushed address.
	setup.NewBuilder()
	setup.AddAddress("10.77.0.99", 24, "10.77.0.1", false, false)
	nativeFD2, err := setup.Establish()
	if err != nil {
		t.Fatalf("Establish #2: %v", err)
	}
	snet2, closePeer2 := attachPeerNetstack(t, nativeFD2, "10.77.0.1")
	defer closePeer2()
	startTCPEcho(t, snet2, "10.77.0.1:7008")

	if got := handle.BindAddr(); got != "10.77.0.99" {
		t.Fatalf("post-reconnect BindAddr() = %q, want 10.77.0.99 (NewBuilder must reset prior addresses and update handle)", got)
	}

	// Dial after reconnect must use the second Establish()'s netstack, not
	// the closed first one.
	conn, err := handle.Dial("10.77.0.1", 7008, 3000)
	if err != nil {
		t.Fatalf("Dial after reconnect: %v", err)
	}
	defer conn.Close()
	if err := conn.Write([]byte("reconnected")); err != nil {
		t.Fatalf("Write after reconnect: %v", err)
	}
	got, err := conn.Read(64)
	if err != nil || string(got) != "reconnected" {
		t.Fatalf("Read after reconnect = %q, %v; want reconnected", got, err)
	}
}

func TestTunnelHandle_FatalEventAfterConnectMarksDead(t *testing.T) {
	setup := newOvpnTunnelSetup()
	setup.NewBuilder()
	setup.AddAddress("10.77.0.2", 24, "10.77.0.1", false, false)
	nativeFD, err := setup.Establish()
	if err != nil {
		t.Fatalf("Establish: %v", err)
	}
	_, closePeer := attachPeerNetstack(t, nativeFD, "10.77.0.1")
	defer closePeer()

	res := <-setup.ready
	handle := &TunnelHandle{
		setup:    setup,
		tnet:     res.tnet,
		bindAddr: res.bindAddr,
	}
	defer handle.Close()
	events := &ovpnEvents{setup: setup, connect: make(chan error, 1), handle: handle}

	if got := handle.LastError(); got != "" {
		t.Fatalf("initial LastError() = %q, want empty", got)
	}

	// Simulate a fatal server-side AUTH_FAILED arriving after connect.
	events.OnEvent(openvpn3.Event{
		Name:  "AUTH_FAILED",
		Info:  "session token expired",
		Error: true,
		Fatal: true,
	})

	if got := handle.LastError(); !strings.Contains(got, "AUTH_FAILED") {
		t.Fatalf("LastError() = %q, want AUTH_FAILED", got)
	}
	if _, err := handle.Dial("10.77.0.1", 80, 500); err == nil || !strings.Contains(err.Error(), "AUTH_FAILED") {
		t.Fatalf("Dial on dead handle = %v, want error mentioning AUTH_FAILED", err)
	}
}

