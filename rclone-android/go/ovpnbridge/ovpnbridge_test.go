package ovpnbridge

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// tunAvailable reports whether this process can create a real kernel TUN
// device. A real `openvpn` server needs CAP_NET_ADMIN + /dev/net/tun to
// even start, well before any client connects -- checked once up front so
// a container without it (common in a sandboxed dev/CI environment) skips
// with a clear, concretely diagnosed reason instead of failing deep inside
// a subprocess whose log format isn't a stable thing to grep for.
func tunAvailable() (bool, string) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR, 0)
	if err != nil {
		return false, fmt.Sprintf("open /dev/net/tun: %v", err)
	}
	defer unix.Close(fd)

	ifr, err := unix.NewIfreq("ovpnprobe0")
	if err != nil {
		return false, fmt.Sprintf("NewIfreq: %v", err)
	}
	ifr.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err := unix.IoctlIfreq(fd, unix.TUNSETIFF, ifr); err != nil {
		return false, fmt.Sprintf("TUNSETIFF: %v (likely missing CAP_NET_ADMIN)", err)
	}
	return true, ""
}

// runEasyRSA generates a throwaway CA + server + client cert chain under
// dir using the system easyrsa package, returning the paths openvpn server
// configs and .ovpn client profiles need.
func runEasyRSA(t *testing.T, dir string) (caCert, serverCert, serverKey, dh, clientCert, clientKey, taKey string) {
	t.Helper()

	pkiDir := filepath.Join(dir, "pki")
	run := func(args ...string) {
		t.Helper()
		cmd := exec.Command("easyrsa", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(), "EASYRSA_BATCH=1", "EASYRSA_PKI="+pkiDir, "EASYRSA_REQ_CN=haven-test")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("easyrsa %v: %v\n%s", args, err, out)
		}
	}

	run("init-pki")
	run("build-ca", "nopass")
	run("build-server-full", "server", "nopass")
	run("build-client-full", "client", "nopass")
	run("gen-dh")

	taKeyPath := filepath.Join(dir, "ta.key")
	if out, err := exec.Command("openvpn", "--genkey", "secret", taKeyPath).CombinedOutput(); err != nil {
		t.Fatalf("openvpn --genkey secret: %v\n%s", err, out)
	}

	return filepath.Join(pkiDir, "ca.crt"),
		filepath.Join(pkiDir, "issued", "server.crt"),
		filepath.Join(pkiDir, "private", "server.key"),
		filepath.Join(pkiDir, "dh.pem"),
		filepath.Join(pkiDir, "issued", "client.crt"),
		filepath.Join(pkiDir, "private", "client.key"),
		taKeyPath
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
// local `openvpn` server process, and drives a real OpenVPN3 Core session
// against it via StartTunnel -- proving Establish() actually ran end to
// end (netstack + socketpair + pump all created, real TLS handshake, real
// PUSH_REPLY parsed for the tunnel address), not just that the code
// compiles. Skips (rather than fails) when this environment can't run a
// real openvpn server at all, per tunAvailable's diagnosis.
func TestStartTunnel_RealHandshake(t *testing.T) {
	if ok, reason := tunAvailable(); !ok {
		t.Skipf("no usable TUN device in this environment (%s) -- a real openvpn server can't start here, so this test can't drive a real handshake", reason)
	}

	dir := t.TempDir()
	caCert, serverCert, serverKey, dh, clientCert, clientKey, taKey := runEasyRSA(t, dir)

	serverConf := filepath.Join(dir, "server.ovpn")
	serverConfBody := fmt.Sprintf(`port 11194
proto udp
dev tun
ca %s
cert %s
key %s
dh %s
tls-auth %s 0
topology subnet
server 10.77.0.0 255.255.255.0
keepalive 10 60
persist-key
persist-tun
verb 3
`, caCert, serverCert, serverKey, dh, taKey)
	if err := os.WriteFile(serverConf, []byte(serverConfBody), 0o600); err != nil {
		t.Fatalf("write server config: %v", err)
	}

	serverCmd := exec.Command("sudo", "openvpn", "--config", serverConf)
	serverOut, err := serverCmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe: %v", err)
	}
	serverCmd.Stderr = serverCmd.Stdout
	if err := serverCmd.Start(); err != nil {
		t.Fatalf("start openvpn server: %v", err)
	}
	t.Cleanup(func() {
		_ = exec.Command("sudo", "kill", fmt.Sprintf("%d", serverCmd.Process.Pid)).Run()
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
				ready <- struct{}{}
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
proto udp
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
}
