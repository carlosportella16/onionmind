package app

import (
	"context"
	"testing"
	"time"

	"github.com/carlosportella16/onionmind/crawler/internal/config"
)

func TestRun_UnreachableBrokerReturnsErrorInsteadOfBlocking(t *testing.T) {
	cfg := &config.Config{
		Crawler: config.CrawlerConfig{Workers: 1, FetchTimeout: config.Duration(time.Second), MaxPageSizeMB: 1,
			HostDenylistPath: "../../denylist/banned-hosts.md5"},
		Tor:      config.TorConfig{SocksAddr: "127.0.0.1:1"},
		Redis:    config.RedisConfig{Addr: "127.0.0.1:1", DedupTTL: config.Duration(time.Minute)},
		Redpanda: config.RedpandaConfig{Brokers: []string{"127.0.0.1:1"}, Topic: "raw-pages"},
		Frontier: config.FrontierConfig{MaxDepth: 1, MaxQueueSize: 10},
	}

	done := make(chan error, 1)
	go func() { done <- Run(context.Background(), cfg) }()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected error creating publisher against unreachable broker")
		}
	case <-time.After(30 * time.Second):
		t.Fatal("Run blocked instead of failing fast on an unreachable broker")
	}
}
