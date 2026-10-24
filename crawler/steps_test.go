//go:build acceptance

package crawler_test

import (
	"context"
	"crypto/md5"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"github.com/cucumber/godog"
	"github.com/redis/go-redis/v9"

	"github.com/carlosportella16/onionmind/crawler/internal/app"
	"github.com/carlosportella16/onionmind/crawler/internal/config"
	"github.com/carlosportella16/onionmind/crawler/internal/normalizer"
	"github.com/carlosportella16/onionmind/crawler/internal/testfakes"
)

const (
	publishWait = 20 * time.Second
	// How long a negative assertion ("not published", "one request") waits for anything late.
	settleWait = 2 * time.Second
)

type message struct {
	Key   string
	Value map[string]any
}

// world is one scenario's state.
type world struct {
	cfg      *config.Config
	banned   []string // hosts written to the crawler's denylist file when it starts
	redis    *redis.Client
	cancel   context.CancelFunc
	done     chan error
	consumer sarama.Consumer

	mu       sync.Mutex
	messages map[string]message
}

func initializeScenario(sc *godog.ScenarioContext) {
	w := &world{}

	sc.Before(func(ctx context.Context, s *godog.Scenario) (context.Context, error) {
		env.sites.Reset()
		env.socks.Reset()
		w.messages = map[string]message{}
		w.banned = nil
		w.redis = redis.NewClient(&redis.Options{Addr: env.redisAddr})
		if err := w.redis.FlushDB(ctx).Err(); err != nil {
			return ctx, err
		}
		topic := "raw-pages-" + strconv.FormatInt(time.Now().UnixNano(), 36)
		w.cfg = &config.Config{
			Crawler: config.CrawlerConfig{
				Workers: 2, FetchTimeout: config.Duration(5 * time.Second), MaxPageSizeMB: 10,
				CircuitBreaker:   config.CircuitBreakerConfig{ConsecutiveFailures: 3, Timeout: config.Duration(30 * time.Second)},
				HostDenylistPath: filepath.Join(env.tmpDir, topic+".md5"), MaxLinksPerPage: 2000,
			},
			Tor:      config.TorConfig{SocksAddr: env.socks.Addr()},
			Redis:    config.RedisConfig{Addr: env.redisAddr, DedupTTL: config.Duration(time.Hour)},
			Redpanda: config.RedpandaConfig{Brokers: []string{env.broker}, Topic: topic},
			Frontier: config.FrontierConfig{MaxQueueSize: 1000},
		}
		return ctx, nil
	})

	sc.After(func(ctx context.Context, s *godog.Scenario, err error) (context.Context, error) {
		if w.cancel != nil {
			w.cancel()
			select {
			case <-w.done:
			case <-time.After(15 * time.Second):
			}
		}
		if w.consumer != nil {
			_ = w.consumer.Close()
		}
		_ = w.redis.Close()
		return ctx, nil
	})

	sc.Step(`^os sites onion abaixo:$`, w.declareSites)
	sc.Step(`^o teto de publicação é de (\d+) MB$`, w.publishCeiling)
	sc.Step(`^o Redis está indisponível$`, w.redisDown)
	sc.Step(`^o crawler roda com a semente "([^"]*)" e profundidade máxima (\d+)$`, w.runCrawler)
	sc.Step(`^a página "([^"]*)" é publicada$`, w.assertPublished)
	sc.Step(`^a página "([^"]*)" não é publicada$`, w.assertNotPublished)
	sc.Step(`^a mensagem de "([^"]*)" tem a URL como chave, source_type "([^"]*)", html contendo "([^"]*)" e fetched_at em RFC 3339$`, w.assertMessageContract)
	sc.Step(`^o proxy recebeu o hostname "([^"]*)"$`, w.assertProxyHost)
	sc.Step(`^o proxy não recebeu requisição para "([^"]*)"$`, w.assertProxyNeverAsked)
	sc.Step(`^o site recebeu (\d+) requisição para "([^"]*)"$`, w.assertHits)
	sc.Step(`^o Redis tem a chave de visita de "([^"]*)" com TTL positivo$`, w.assertSeenKey)
	sc.Step(`^o Redis tem um registro de página grande com a URL "([^"]*)" e o tamanho$`, w.assertOversizedRecord)
	sc.Step(`^o crawler é cancelado$`, w.cancelCrawler)
	sc.Step(`^a execução termina sem erro em até (\d+) segundos$`, w.assertStopped)
}

func (w *world) declareSites(table *godog.Table) error {
	header := table.Rows[0].Cells
	for _, row := range table.Rows[1:] {
		cols := map[string]string{}
		for i, cell := range row.Cells {
			cols[header[i].Value] = cell.Value
		}
		page := testfakes.Page{HTML: cols["html"]}
		if v := cols["status"]; v != "" {
			page.Status, _ = strconv.Atoi(v)
		}
		if v := cols["tamanho_mb"]; v != "" {
			mb, _ := strconv.Atoi(v)
			page.PadToBytes = mb << 20
		}
		env.sites.Set(cols["url"], page)
	}
	return nil
}

func (w *world) publishCeiling(mb int) error {
	w.cfg.Crawler.MaxPageSizeMB = mb
	return nil
}

func (w *world) redisDown() error {
	w.cfg.Redis.Addr = "127.0.0.1:1" // nothing listens there
	return nil
}

func (w *world) runCrawler(seed string, depth int) error {
	w.cfg.Frontier.Seeds = []string{seed}
	w.cfg.Frontier.MaxDepth = depth
	var list strings.Builder
	for _, host := range w.banned {
		fmt.Fprintf(&list, "%x\n", md5.Sum([]byte(host)))
	}
	if err := os.WriteFile(w.cfg.Crawler.HostDenylistPath, []byte(list.String()), 0o600); err != nil {
		return err
	}
	if err := w.consume(); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	w.cancel = cancel
	w.done = make(chan error, 1)
	go func() { w.done <- app.Run(ctx, w.cfg) }()
	return nil
}

// consume creates the scenario's topic and collects every message on it in the background.
func (w *world) consume() error {
	sc := sarama.NewConfig()
	sc.Consumer.Offsets.Initial = sarama.OffsetOldest
	admin, err := sarama.NewClusterAdmin([]string{env.broker}, sc)
	if err != nil {
		return err
	}
	defer admin.Close()
	if err := admin.CreateTopic(w.cfg.Redpanda.Topic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1}, false); err != nil {
		return fmt.Errorf("create topic: %w", err)
	}

	if w.consumer, err = sarama.NewConsumer([]string{env.broker}, sc); err != nil {
		return err
	}
	pc, err := w.consumer.ConsumePartition(w.cfg.Redpanda.Topic, 0, sarama.OffsetOldest)
	if err != nil {
		return err
	}
	go func() {
		for msg := range pc.Messages() {
			var value map[string]any
			_ = json.Unmarshal(msg.Value, &value)
			w.mu.Lock()
			w.messages[fmt.Sprint(value["url"])] = message{Key: string(msg.Key), Value: value}
			w.mu.Unlock()
		}
	}()
	return nil
}

func (w *world) message(url string) (message, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	m, ok := w.messages[url]
	return m, ok
}

func (w *world) assertPublished(url string) error {
	deadline := time.Now().Add(publishWait)
	for time.Now().Before(deadline) {
		if _, ok := w.message(url); ok {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	return fmt.Errorf("%s not published within %s; published: %v", url, publishWait, keys(w.messages))
}

func (w *world) assertNotPublished(url string) error {
	time.Sleep(settleWait)
	if _, ok := w.message(url); ok {
		return fmt.Errorf("%s should not have been published", url)
	}
	return nil
}

func (w *world) assertMessageContract(url, sourceType, htmlFragment string) error {
	m, ok := w.message(url)
	if !ok {
		return fmt.Errorf("%s not published", url)
	}
	if m.Key != url {
		return fmt.Errorf("key = %q, want the url %q", m.Key, url)
	}
	if m.Value["source_type"] != sourceType {
		return fmt.Errorf("source_type = %v, want %q", m.Value["source_type"], sourceType)
	}
	html, _ := m.Value["html"].(string)
	if !strings.Contains(html, htmlFragment) {
		return fmt.Errorf("html should be readable text containing %q, got %.80q", htmlFragment, html)
	}
	fetched, _ := m.Value["fetched_at"].(string)
	if _, err := time.Parse(time.RFC3339, fetched); err != nil {
		return fmt.Errorf("fetched_at %q is not RFC 3339: %w", fetched, err)
	}
	return nil
}

func (w *world) assertProxyHost(host string) error {
	if !slices.Contains(env.socks.Hosts(), host) {
		return fmt.Errorf("proxy never received hostname %q; got %v", host, env.socks.Hosts())
	}
	return nil
}

func (w *world) assertProxyNeverAsked(host string) error {
	time.Sleep(settleWait)
	if slices.Contains(env.socks.Hosts(), host) {
		return fmt.Errorf("crawler asked the proxy for %q", host)
	}
	return nil
}

func (w *world) assertHits(n int, url string) error {
	time.Sleep(settleWait)
	if got := env.sites.Hits(url); got != n {
		return fmt.Errorf("site got %d request(s) for %s, want %d", got, url, n)
	}
	return nil
}

func (w *world) assertSeenKey(url string) error {
	ttl, err := w.redis.TTL(context.Background(), "seen:"+normalizer.Normalize(url)).Result()
	if err != nil {
		return err
	}
	if ttl <= 0 {
		return fmt.Errorf("seen key for %s has TTL %v, want positive", url, ttl)
	}
	return nil
}

func (w *world) assertOversizedRecord(url string) error {
	ctx := context.Background()
	deadline := time.Now().Add(publishWait)
	for time.Now().Before(deadline) {
		found, err := w.hasOversizedRecord(ctx, url)
		if err != nil || found {
			return err
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("no skipped:oversized record for %s", url)
}

// hasOversizedRecord reports whether some skipped:oversized value ("url|size|time") names
// url with a positive size.
func (w *world) hasOversizedRecord(ctx context.Context, url string) (bool, error) {
	keys, err := w.redis.Keys(ctx, "skipped:oversized:*").Result()
	if err != nil {
		return false, err
	}
	for _, k := range keys {
		v, err := w.redis.Get(ctx, k).Result()
		if err != nil {
			continue
		}
		parts := strings.Split(v, "|")
		if len(parts) < 2 || parts[0] != url {
			continue
		}
		if size, _ := strconv.Atoi(parts[1]); size > 0 {
			return true, nil
		}
	}
	return false, nil
}

func (w *world) cancelCrawler() error {
	if w.cancel == nil {
		return errors.New("crawler is not running")
	}
	w.cancel()
	return nil
}

func (w *world) assertStopped(seconds int) error {
	select {
	case err := <-w.done:
		w.cancel = nil // already stopped; After must not wait again
		return err
	case <-time.After(time.Duration(seconds) * time.Second):
		return fmt.Errorf("crawler did not stop within %ds", seconds)
	}
}

func keys(m map[string]message) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	slices.Sort(out)
	return out
}
