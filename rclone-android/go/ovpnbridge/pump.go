package ovpnbridge

import (
	"fmt"
	"os"
	"sync"
	"syscall"

	"golang.zx2c4.com/wireguard/tun"
)

type packetPump struct {
	file     *os.File
	dev      tun.Device
	wg       sync.WaitGroup
	closeErr error
	closeOne sync.Once
}

func newPacketPump(nativeFD int, dev tun.Device) (*packetPump, error) {
	if err := syscall.SetNonblock(nativeFD, true); err != nil {
		return nil, fmt.Errorf("set nonblocking: %w", err)
	}
	f := os.NewFile(uintptr(nativeFD), "ovpn-socketpair")
	if f == nil {
		return nil, fmt.Errorf("os.NewFile failed for fd %d", nativeFD)
	}
	p := &packetPump{file: f, dev: dev}
	p.wg.Add(2)
	go p.pumpFromNativeToTun()
	go p.pumpFromTunToNative()
	return p, nil
}

func (p *packetPump) pumpFromNativeToTun() {
	defer p.wg.Done()
	buf := make([]byte, 2048)
	for {
		n, err := p.file.Read(buf)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		if _, err := p.dev.Write([][]byte{buf[:n]}, 0); err != nil {
			return
		}
	}
}

func (p *packetPump) pumpFromTunToNative() {
	defer p.wg.Done()
	bufs := make([][]byte, 1)
	bufs[0] = make([]byte, 2048)
	sizes := make([]int, 1)
	for {
		n, err := p.dev.Read(bufs, sizes, 0)
		if err != nil {
			return
		}
		for i := 0; i < n; i++ {
			if _, err := p.file.Write(bufs[i][:sizes[i]]); err != nil {
				return
			}
		}
	}
}

func (p *packetPump) Close() error {
	p.closeOne.Do(func() {
		fileErr := p.file.Close()
		devErr := p.dev.Close()
		p.wg.Wait()
		if fileErr != nil {
			p.closeErr = fileErr
			return
		}
		p.closeErr = devErr
	})
	return p.closeErr
}
