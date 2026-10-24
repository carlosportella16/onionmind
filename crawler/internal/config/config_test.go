package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadResolvesDenylistAndDefaultsLinksCap(t *testing.T) {
	cfg, err := Load("../../config.yaml")
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}
	if cfg.Crawler.MaxLinksPerPage != 2000 {
		t.Errorf("MaxLinksPerPage = %d, want 2000", cfg.Crawler.MaxLinksPerPage)
	}
	if want := filepath.Join("..", "..", "denylist", "banned-hosts.md5"); cfg.Crawler.HostDenylistPath != want {
		t.Errorf("HostDenylistPath = %q, want %q (relative to the config file)", cfg.Crawler.HostDenylistPath, want)
	}

	path := writeConfig(t, "crawler:\n  workers: 1\n  host_denylist_path: \"banned.md5\"\n")
	if err := os.WriteFile(filepath.Join(filepath.Dir(path), "banned.md5"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if cfg, err = Load(path); err != nil {
		t.Fatalf("Load returned error: %v", err)
	}
	if cfg.Crawler.MaxLinksPerPage != 2000 {
		t.Errorf("unset MaxLinksPerPage = %d, want default 2000", cfg.Crawler.MaxLinksPerPage)
	}
}

func TestLoadFailsWithoutDenylistFile(t *testing.T) {
	cases := []struct{ name, yaml, inErr string }{
		{"missing path", "crawler:\n  host_denylist_path: \"does-not-exist.md5\"\n", "does-not-exist.md5"},
		{"unset path", "crawler:\n  workers: 1\n", "host_denylist_path"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := Load(writeConfig(t, c.yaml))
			if err == nil || !strings.Contains(err.Error(), c.inErr) {
				t.Fatalf("expected an error containing %q, got %v", c.inErr, err)
			}
		})
	}
}

func writeConfig(t *testing.T, yaml string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(yaml), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadParsesExampleConfig(t *testing.T) {
	cfg, err := Load("../../config.yaml")
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	if cfg.Crawler.Workers != 8 {
		t.Errorf("Workers = %d, want 8", cfg.Crawler.Workers)
	}
	if cfg.Crawler.FetchTimeout.Duration() != 45*time.Second {
		t.Errorf("FetchTimeout = %v, want 45s", cfg.Crawler.FetchTimeout.Duration())
	}
	if cfg.Crawler.PolitenessDelay.Duration() != 3*time.Second {
		t.Errorf("PolitenessDelay = %v, want 3s", cfg.Crawler.PolitenessDelay.Duration())
	}
	if cfg.Crawler.CircuitBreaker.ConsecutiveFailures != 3 {
		t.Errorf("ConsecutiveFailures = %d, want 3", cfg.Crawler.CircuitBreaker.ConsecutiveFailures)
	}
	if cfg.Crawler.CircuitBreaker.Timeout.Duration() != 30*time.Second {
		t.Errorf("CircuitBreaker.Timeout = %v, want 30s", cfg.Crawler.CircuitBreaker.Timeout.Duration())
	}
	if cfg.Tor.SocksAddr != "tor:9050" {
		t.Errorf("Tor.SocksAddr = %q, want %q", cfg.Tor.SocksAddr, "tor:9050")
	}
	if cfg.Redis.Addr != "redis:6379" {
		t.Errorf("Redis.Addr = %q, want %q", cfg.Redis.Addr, "redis:6379")
	}
	if cfg.Redis.DedupTTL.Duration() != 6*time.Hour {
		t.Errorf("Redis.DedupTTL = %v, want 6h", cfg.Redis.DedupTTL.Duration())
	}
	if len(cfg.Redpanda.Brokers) != 1 || cfg.Redpanda.Brokers[0] != "redpanda:9092" {
		t.Errorf("Redpanda.Brokers = %v, want [redpanda:9092]", cfg.Redpanda.Brokers)
	}
	if cfg.Redpanda.Topic != "raw-pages" {
		t.Errorf("Redpanda.Topic = %q, want raw-pages", cfg.Redpanda.Topic)
	}
	if len(cfg.Frontier.Seeds) != 2 {
		t.Errorf("Frontier.Seeds = %v, want 2 seeds", cfg.Frontier.Seeds)
	}
	if cfg.Frontier.MaxDepth != 3 {
		t.Errorf("Frontier.MaxDepth = %d, want 3", cfg.Frontier.MaxDepth)
	}
	if cfg.Frontier.MaxQueueSize != 100000 {
		t.Errorf("Frontier.MaxQueueSize = %d, want 100000", cfg.Frontier.MaxQueueSize)
	}
}
