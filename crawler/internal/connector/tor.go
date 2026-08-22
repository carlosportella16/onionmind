package connector

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/sony/gobreaker/v2"
	"golang.org/x/net/proxy"
)

type TorConnector struct {
	client  *http.Client
	breaker *gobreaker.CircuitBreaker[[]byte]
}

func NewTorConnector(socksAddr string, fetchTimeout time.Duration, cbSettings gobreaker.Settings) (*TorConnector, error) {
	dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return nil, fmt.Errorf("socks5 dial: %w", err)
	}

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// socks5h — .onion resolution happens at the proxy, not locally
			return dialer.Dial(network, addr)
		},
		MaxIdleConns:      100,
		IdleConnTimeout:   90 * time.Second,
		DisableKeepAlives: false,
		MaxConnsPerHost:   2, // politeness: don't open many connections to the same .onion
	}

	cb := gobreaker.NewCircuitBreaker[[]byte](cbSettings)

	return &TorConnector{
		client:  &http.Client{Transport: transport, Timeout: fetchTimeout},
		breaker: cb,
	}, nil
}

func (t *TorConnector) ID() string { return "tor" }

func (t *TorConnector) Fetch(ctx context.Context, targetURL string) (*RawPage, error) {
	body, err := t.breaker.Execute(func() ([]byte, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
		if err != nil {
			return nil, fmt.Errorf("build request: %w", err)
		}
		req.Header.Set("User-Agent", "OnionMind/0.1 (research crawler)")

		resp, err := t.client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("fetch: %w", err)
		}
		defer resp.Body.Close()

		if resp.StatusCode >= 400 {
			return nil, fmt.Errorf("http %d for %s", resp.StatusCode, targetURL)
		}

		// 10MB cap — larger pages are truncated, not rejected
		return io.ReadAll(io.LimitReader(resp.Body, 10<<20))
	})
	if err != nil {
		return nil, err
	}

	return &RawPage{
		URL:        targetURL,
		SourceType: t.ID(),
		HTML:       body,
		FetchedAt:  time.Now().UTC().Format(time.RFC3339),
	}, nil
}
