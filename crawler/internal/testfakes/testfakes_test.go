package testfakes

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

func newNetwork(t *testing.T) (*OnionSites, *SOCKS5Server, *http.Client) {
	t.Helper()
	sites := NewOnionSites()
	upstream := httptest.NewServer(sites)
	t.Cleanup(upstream.Close)

	socks, err := NewSOCKS5Server("127.0.0.1:0", strings.TrimPrefix(upstream.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = socks.Close() })

	dialer, err := proxy.SOCKS5("tcp", socks.Addr(), nil, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.Dial(network, addr)
		},
	}}
	return sites, socks, client
}

func TestOnionHostIsServedThroughTheProxyWithoutLocalResolution(t *testing.T) {
	sites, socks, client := newNetwork(t)
	sites.Set("http://alfa.onion/", Page{HTML: "<p>alfa</p>"})

	resp, err := client.Get("http://alfa.onion/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()

	if resp.StatusCode != 200 || string(body) != "<p>alfa</p>" {
		t.Fatalf("got %d %q", resp.StatusCode, body)
	}
	if !slices.Contains(socks.Hosts(), "alfa.onion") {
		t.Fatalf("proxy should receive the hostname, got %v", socks.Hosts())
	}
	if sites.Hits("http://alfa.onion/") != 1 {
		t.Fatalf("expected 1 hit, got %d", sites.Hits("http://alfa.onion/"))
	}
}

func TestClearnetHostIsRefusedAndRecorded(t *testing.T) {
	_, socks, client := newNetwork(t)

	if _, err := client.Get("http://exemplo.com/"); err == nil {
		t.Fatal("expected clearnet host to be refused")
	}
	if !slices.Contains(socks.Refused(), "exemplo.com") {
		t.Fatalf("expected exemplo.com recorded as refused, got %v", socks.Refused())
	}
}

func TestPagesHonourStatusPaddingAndUnknownPaths(t *testing.T) {
	sites, socks, client := newNetwork(t)
	sites.Set("http://erro.onion/", Page{HTML: "falha", Status: 500})
	sites.Set("http://grande.onion/", Page{HTML: "<p>x</p>", PadToBytes: 4096})

	cases := map[string]struct {
		status int
		minLen int
	}{
		"http://erro.onion/":     {500, 1},
		"http://grande.onion/":   {200, 4096},
		"http://inexiste.onion/": {404, 1},
	}
	for url, want := range cases {
		resp, err := client.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != want.status || len(body) < want.minLen {
			t.Errorf("%s: got %d len %d", url, resp.StatusCode, len(body))
		}
	}

	sites.Reset()
	socks.Reset()
	if sites.Hits("http://erro.onion/") != 0 || len(socks.Hosts()) != 0 {
		t.Fatal("reset should clear counters and recorded hosts")
	}
}
