package config

import (
	"testing"
	"time"
)

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
