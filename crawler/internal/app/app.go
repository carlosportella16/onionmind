// Package app wires the crawler's components together. Kept out of cmd/crawler so the
// real wiring can be driven by the acceptance suite with a cancellable context.
package app

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/sony/gobreaker/v2"

	"github.com/carlosportella16/onionmind/crawler/internal/config"
	"github.com/carlosportella16/onionmind/crawler/internal/connector"
	"github.com/carlosportella16/onionmind/crawler/internal/dedup"
	"github.com/carlosportella16/onionmind/crawler/internal/denylist"
	"github.com/carlosportella16/onionmind/crawler/internal/frontier"
	"github.com/carlosportella16/onionmind/crawler/internal/publisher"
	"github.com/carlosportella16/onionmind/crawler/internal/worker"
)

// Run builds the Tor connector, dedup, publisher, frontier and worker pool from cfg and
// crawls until ctx is cancelled. It returns an error only if bootstrap fails.
func Run(ctx context.Context, cfg *config.Config) error {
	deny, err := denylist.Load(cfg.Crawler.HostDenylistPath)
	if err != nil {
		return err
	}

	tor, err := connector.NewTorConnector(cfg.Tor.SocksAddr, cfg.Crawler.FetchTimeout.Duration(),
		gobreaker.Settings{
			Name:    "tor-fetch",
			Timeout: cfg.Crawler.CircuitBreaker.Timeout.Duration(),
			ReadyToTrip: func(c gobreaker.Counts) bool {
				return c.ConsecutiveFailures > uint32(cfg.Crawler.CircuitBreaker.ConsecutiveFailures)
			},
		})
	if err != nil {
		return fmt.Errorf("create tor connector: %w", err)
	}

	d := dedup.New(cfg.Redis.Addr, cfg.Redis.DedupTTL.Duration())
	pub, err := publisher.New(cfg.Redpanda.Brokers, cfg.Redpanda.Topic, cfg.Crawler.MaxPageSizeMB)
	if err != nil {
		return fmt.Errorf("create publisher: %w", err)
	}
	defer pub.Close()

	f := frontier.New(cfg.Frontier.Seeds, cfg.Frontier.MaxQueueSize, cfg.Frontier.MaxDepth, deny)

	slog.Info("starting crawler", "workers", cfg.Crawler.Workers, "seeds", len(cfg.Frontier.Seeds))
	worker.New(cfg.Crawler.Workers, tor, d, pub, f, deny, cfg.Crawler.PolitenessDelay.Duration()).Run(ctx)
	slog.Info("crawler stopped")
	return nil
}
