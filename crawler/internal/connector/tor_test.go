package connector

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sony/gobreaker/v2"
)

// newTestConnector builds a TorConnector that talks directly to the given
// server, bypassing the real SOCKS5 dial (per task 6.3: mock the dialer).
func newTestConnector(cbSettings gobreaker.Settings) *TorConnector {
	return &TorConnector{
		client:  &http.Client{Timeout: 5 * time.Second},
		breaker: gobreaker.NewCircuitBreaker[[]byte](cbSettings),
	}
}

func TestNewTorConnector_BuildsWithoutDialing(t *testing.T) {
	// Dialer construction is lazy — no connection is opened at this point,
	// so this must succeed even without a real Tor proxy reachable.
	conn, err := NewTorConnector("127.0.0.1:1", 5*time.Second, gobreaker.Settings{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if conn.ID() != "tor" {
		t.Errorf("ID() = %q, want tor", conn.ID())
	}
}

func TestFetch_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("hello onion"))
	}))
	defer srv.Close()

	conn := newTestConnector(gobreaker.Settings{})
	page, err := conn.Fetch(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(page.HTML) != "hello onion" {
		t.Errorf("HTML = %q", page.HTML)
	}
	if page.SourceType != "tor" {
		t.Errorf("SourceType = %q, want tor", page.SourceType)
	}
}

func TestFetch_HTTPErrorStatusReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	conn := newTestConnector(gobreaker.Settings{})
	_, err := conn.Fetch(context.Background(), srv.URL)
	if err == nil {
		t.Fatal("expected error for HTTP 404, got nil")
	}
}

func TestFetch_ResponseLimitedTo10MB(t *testing.T) {
	const limit = 10 << 20
	oversized := strings.Repeat("a", limit+1024)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(oversized))
	}))
	defer srv.Close()

	conn := newTestConnector(gobreaker.Settings{})
	page, err := conn.Fetch(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(page.HTML) != limit {
		t.Errorf("len(HTML) = %d, want %d", len(page.HTML), limit)
	}
}

func TestFetch_CircuitBreakerOpensAfterConsecutiveFailures(t *testing.T) {
	var requests int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&requests, 1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	conn := newTestConnector(gobreaker.Settings{
		Timeout: time.Minute,
		ReadyToTrip: func(c gobreaker.Counts) bool {
			return c.ConsecutiveFailures >= 3
		},
	})

	for i := 0; i < 3; i++ {
		if _, err := conn.Fetch(context.Background(), srv.URL); err == nil {
			t.Fatalf("attempt %d: expected error", i)
		}
	}

	afterThreeFailures := atomic.LoadInt32(&requests)

	_, err := conn.Fetch(context.Background(), srv.URL)
	if err == nil {
		t.Fatal("expected circuit-open error on 4th call")
	}
	if atomic.LoadInt32(&requests) != afterThreeFailures {
		t.Errorf("expected no new network request once circuit is open, requests went from %d to %d",
			afterThreeFailures, requests)
	}
}
