package tsbridge

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"

	dns "golang.org/x/net/dns/dnsmessage"
)

// buildAResponse builds a DNS response for qname carrying one A record for addr.
func buildAResponse(t *testing.T, qname string, addr netip.Addr) []byte {
	t.Helper()
	q := dns.Question{
		Name:  dns.MustNewName(qname),
		Type:  dns.TypeA,
		Class: dns.ClassINET,
	}
	b := dns.NewBuilder(nil, dns.Header{Response: true, RecursionDesired: true})
	b.StartQuestions()
	if err := b.Question(q); err != nil {
		t.Fatalf("question: %v", err)
	}
	b.StartAnswers()
	if err := b.AResource(dns.ResourceHeader{
		Name:  q.Name,
		Class: dns.ClassINET,
		TTL:   300,
	}, dns.AResource{A: addr.As4()}); err != nil {
		t.Fatalf("aresource: %v", err)
	}
	resp, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	return resp
}

// buildAMultiResponse builds a DNS response for qname carrying one A record
// per address, in order.
func buildAMultiResponse(t *testing.T, qname string, addrs ...netip.Addr) []byte {
	t.Helper()
	q := dns.Question{
		Name:  dns.MustNewName(qname),
		Type:  dns.TypeA,
		Class: dns.ClassINET,
	}
	b := dns.NewBuilder(nil, dns.Header{Response: true, RecursionDesired: true})
	b.StartQuestions()
	if err := b.Question(q); err != nil {
		t.Fatalf("question: %v", err)
	}
	b.StartAnswers()
	for _, addr := range addrs {
		if err := b.AResource(dns.ResourceHeader{
			Name:  q.Name,
			Class: dns.ClassINET,
			TTL:   300,
		}, dns.AResource{A: addr.As4()}); err != nil {
			t.Fatalf("aresource: %v", err)
		}
	}
	resp, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	return resp
}

func TestParseAllASingle(t *testing.T) {
	want := netip.MustParseAddr("100.101.102.103")
	resp := buildAResponse(t, "homelab.example.", want)
	got, ok := parseAllA(resp)
	if !ok {
		t.Fatalf("parseAllA ok=false, want true")
	}
	if len(got) != 1 || got[0] != want.String() {
		t.Errorf("parseAllA = %v, want [%q]", got, want.String())
	}
}

// TestParseAllAMultiple is the #691 follow-up: a nameserver returning two A
// records must yield BOTH, in order, so the dial path can try each when only
// one is reachable. The old parseFirstA dropped the second.
func TestParseAllAMultiple(t *testing.T) {
	a := netip.MustParseAddr("100.101.102.103")
	b := netip.MustParseAddr("100.101.102.104")
	resp := buildAMultiResponse(t, "homelab.example.", a, b)
	got, ok := parseAllA(resp)
	if !ok {
		t.Fatalf("parseAllA ok=false, want true")
	}
	if len(got) != 2 || got[0] != a.String() || got[1] != b.String() {
		t.Errorf("parseAllA = %v, want [%q %q]", got, a.String(), b.String())
	}
}

func TestParseAllANoAnswer(t *testing.T) {
	// A response with a question but no answer section (NXDOMAIN/NODATA)
	// must report ok=false so the caller falls back to the plain dial.
	b := dns.NewBuilder(nil, dns.Header{Response: true})
	b.StartQuestions()
	if err := b.Question(dns.Question{
		Name:  dns.MustNewName("missing.example."),
		Type:  dns.TypeA,
		Class: dns.ClassINET,
	}); err != nil {
		t.Fatalf("question: %v", err)
	}
	resp, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	if got, ok := parseAllA(resp); ok {
		t.Errorf("parseAllA = (%v, true), want ok=false for a no-answer response", got)
	}
}

func TestParseAllAGarbage(t *testing.T) {
	// Truncated / non-DNS bytes must not panic and must report ok=false.
	if got, ok := parseAllA([]byte{0x00, 0x01}); ok {
		t.Errorf("parseAllA = (%v, true), want ok=false for garbage", got)
	}
}

// TestDialFirstReachableSkipsUnreachable is the #691 follow-up behaviour:
// when the first address fails, the second must still be tried and win.
func TestDialFirstReachableSkipsUnreachable(t *testing.T) {
	var dialed []string
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		dialed = append(dialed, addr)
		if len(dialed) == 1 {
			return nil, errors.New("no route to host")
		}
		return &net.TCPConn{}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	c, err := dialFirstReachable(ctx, []string{"10.0.0.1", "10.0.0.2"}, 22, dial)
	if err != nil {
		t.Fatalf("dialFirstReachable err=%v, want success via second address", err)
	}
	if c == nil {
		t.Fatalf("dialFirstReachable returned nil conn")
	}
	if len(dialed) != 2 || dialed[0] != "10.0.0.1:22" || dialed[1] != "10.0.0.2:22" {
		t.Errorf("dialed = %v, want [10.0.0.1:22 10.0.0.2:22]", dialed)
	}
}

// TestDialFirstReachableAllFail returns the last error when nothing connects.
func TestDialFirstReachableAllFail(t *testing.T) {
	sentinel := errors.New("connection refused")
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		return nil, sentinel
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_, err := dialFirstReachable(ctx, []string{"10.0.0.1", "10.0.0.2"}, 22, dial)
	if !errors.Is(err, sentinel) {
		t.Errorf("err = %v, want %v", err, sentinel)
	}
}

// TestDialFirstReachableBlackHoledFirstIsBounded proves the budget split: a
// first address that hangs (until its per-attempt deadline) must not consume
// the whole budget — the second address is still reached well before the
// overall deadline.
func TestDialFirstReachableBlackHoledFirstIsBounded(t *testing.T) {
	hang := make(chan struct{})
	defer close(hang)
	var attempts int
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		attempts++
		if attempts == 1 {
			<-ctx.Done() // simulate a black-holed address: hangs to its deadline
			return nil, ctx.Err()
		}
		return &net.TCPConn{}, nil
	}
	// 6s overall budget, 2 addresses -> ~3s per attempt (>= the 2s floor).
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	start := time.Now()
	c, err := dialFirstReachable(ctx, []string{"10.0.0.1", "10.0.0.2"}, 22, dial)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("err = %v, want success via second address", err)
	}
	if c == nil {
		t.Fatalf("nil conn")
	}
	if attempts != 2 {
		t.Errorf("attempts = %d, want 2", attempts)
	}
	if elapsed >= 6*time.Second {
		t.Errorf("elapsed %v, want well under the 6s overall budget (per-attempt split failed)", elapsed)
	}
}
