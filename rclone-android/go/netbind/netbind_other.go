//go:build !android

package netbind

import (
	"context"
	"net"
)

func platformBindSocket(_ uint64, _ uintptr) error {
	return nil
}

func platformLookupHost(ctx context.Context, _ uint64, host string) ([]string, error) {
	return net.DefaultResolver.LookupHost(ctx, host)
}
