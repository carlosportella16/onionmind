//go:build acceptance

package crawler_test

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/cucumber/godog"
	"github.com/testcontainers/testcontainers-go"
	tcredis "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/modules/redpanda"

	"github.com/carlosportella16/onionmind/crawler/internal/testfakes"
)

// Started once per run in TestFeatures and shared by every scenario; each scenario gets its
// own topic and a flushed Redis instead (design D3).
var env struct {
	redisAddr string
	broker    string
	sites     *testfakes.OnionSites
	socks     *testfakes.SOCKS5Server
	tmpDir    string // per-scenario host denylist files
}

// TestFeatures runs crawler/features/*.feature against the real crawler wiring (app.Run),
// real Redis/Redpanda containers and a fake Tor (SOCKS5) network. Behind the acceptance
// build tag so a plain `go test ./...` stays Docker-free.
func TestFeatures(t *testing.T) {
	ctx := context.Background()
	env.tmpDir = t.TempDir()

	redisC, err := tcredis.Run(ctx, "redis:7-alpine")
	testcontainers.CleanupContainer(t, redisC)
	if err != nil {
		t.Fatalf("start redis: %v", err)
	}
	redisURL, err := redisC.ConnectionString(ctx)
	if err != nil {
		t.Fatal(err)
	}
	env.redisAddr = strings.TrimPrefix(redisURL, "redis://")

	// Same broker image as docker-compose.yml.
	rp, err := redpanda.Run(ctx, "redpandadata/redpanda:v24.2.7")
	testcontainers.CleanupContainer(t, rp)
	if err != nil {
		t.Fatalf("start redpanda: %v", err)
	}
	if env.broker, err = rp.KafkaSeedBroker(ctx); err != nil {
		t.Fatal(err)
	}

	env.sites = testfakes.NewOnionSites()
	upstream := httptest.NewServer(env.sites)
	t.Cleanup(upstream.Close)
	if env.socks, err = testfakes.NewSOCKS5Server("127.0.0.1:0", strings.TrimPrefix(upstream.URL, "http://")); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = env.socks.Close() })

	suite := godog.TestSuite{
		Name:                "crawler",
		ScenarioInitializer: initializeScenario,
		Options: &godog.Options{
			Format:   "pretty",
			Paths:    []string{"features"},
			Strict:   true,
			TestingT: t,
		},
	}
	if suite.Run() != 0 {
		t.Fatal("acceptance scenarios failed")
	}
}
