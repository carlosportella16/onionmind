package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/sony/gobreaker/v2"

	"github.com/carlosportella16/onionmind/crawler/internal/config"
	"github.com/carlosportella16/onionmind/crawler/internal/connector"
	"github.com/carlosportella16/onionmind/crawler/internal/dedup"
	"github.com/carlosportella16/onionmind/crawler/internal/frontier"
	"github.com/carlosportella16/onionmind/crawler/internal/publisher"
	"github.com/carlosportella16/onionmind/crawler/internal/worker"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	if err := run("config.yaml"); err != nil {
		slog.Error("crawler failed", "err", err)
		os.Exit(1)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("load config: %w", err)
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
	pub, err := publisher.New(cfg.Redpanda.Brokers, cfg.Redpanda.Topic)
	if err != nil {
		return fmt.Errorf("create publisher: %w", err)
	}
	defer pub.Close()

	f := frontier.New(cfg.Frontier.Seeds, cfg.Frontier.MaxQueueSize, cfg.Frontier.MaxDepth)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		slog.Info("shutdown signal received, draining workers...")
		cancel()
	}()

	slog.Info("starting crawler", "workers", cfg.Crawler.Workers, "seeds", len(cfg.Frontier.Seeds))
	pool := worker.New(cfg.Crawler.Workers, tor, d, pub, f, cfg.Crawler.PolitenessDelay.Duration())
	pool.Run(ctx)
	slog.Info("crawler stopped")
	return nil
}
