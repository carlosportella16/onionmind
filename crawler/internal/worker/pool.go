package worker

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/carlosportella16/onionmind/crawler/internal/connector"
	"github.com/carlosportella16/onionmind/crawler/internal/frontier"
	"github.com/carlosportella16/onionmind/crawler/internal/normalizer"
)

// Deduper and Publisher describe the subset of *dedup.Dedup and
// *publisher.Publisher that the worker pool needs — kept as interfaces so
// the pool can be unit-tested with fakes instead of live Redis/Redpanda.
type Deduper interface {
	SeenRecently(canonicalURL string) bool
	MarkSeen(canonicalURL string)
}

type Publisher interface {
	PublishRawPage(page *connector.RawPage) error
}

// Frontier describes the subset of *frontier.Frontier the pool needs.
type Frontier interface {
	Jobs() <-chan frontier.URLJob
	Enqueue(parentDepth int, rawHTML []byte, baseURL string)
}

type Pool struct {
	workers        int
	conn           connector.SourceConnector
	dedup          Deduper
	pub            Publisher
	frontier       Frontier
	politenessWait time.Duration
}

func New(n int, conn connector.SourceConnector, d Deduper, p Publisher, f Frontier, politeness time.Duration) *Pool {
	return &Pool{workers: n, conn: conn, dedup: d, pub: p, frontier: f, politenessWait: politeness}
}

func (p *Pool) Run(ctx context.Context) {
	var wg sync.WaitGroup
	for i := 0; i < p.workers; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			p.work(ctx, id)
		}(i)
	}
	wg.Wait()
}

func (p *Pool) work(ctx context.Context, id int) {
	for {
		select {
		case <-ctx.Done():
			slog.Info("worker shutting down", "worker", id)
			return
		case job, ok := <-p.frontier.Jobs():
			if !ok {
				return
			}
			canon := normalizer.Normalize(job.URL)
			if p.dedup.SeenRecently(canon) {
				continue
			}

			page, err := p.conn.Fetch(ctx, job.URL)
			if err != nil {
				slog.Warn("fetch failed", "worker", id, "url", job.URL, "err", err)
				continue
			}

			p.dedup.MarkSeen(canon)

			if err := p.pub.PublishRawPage(page); err != nil {
				slog.Error("publish failed", "worker", id, "url", job.URL, "err", err)
				continue
			}

			// discover new links within the collected page
			p.frontier.Enqueue(job.Depth, page.HTML, job.URL)

			// politeness: don't overload the same .onion
			time.Sleep(p.politenessWait)
		}
	}
}
