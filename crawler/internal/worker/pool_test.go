package worker

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/carlosportella16/onionmind/crawler/internal/connector"
	"github.com/carlosportella16/onionmind/crawler/internal/frontier"
)

type fakeConnector struct {
	page *connector.RawPage
	err  error
}

func (f *fakeConnector) ID() string { return "fake" }
func (f *fakeConnector) Fetch(ctx context.Context, url string) (*connector.RawPage, error) {
	if f.err != nil {
		return nil, f.err
	}
	return f.page, nil
}

type fakeDedup struct {
	mu   sync.Mutex
	seen map[string]bool
}

func newFakeDedup() *fakeDedup { return &fakeDedup{seen: map[string]bool{}} }

func (d *fakeDedup) SeenRecently(url string) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.seen[url]
}
func (d *fakeDedup) MarkSeen(url string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.seen[url] = true
}

type fakePublisher struct {
	mu        sync.Mutex
	published []*connector.RawPage
	err       error
}

func (p *fakePublisher) PublishRawPage(page *connector.RawPage) error {
	if p.err != nil {
		return p.err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.published = append(p.published, page)
	return nil
}
func (p *fakePublisher) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.published)
}

type fakeFrontier struct {
	jobs        chan frontier.URLJob
	enqueueCall chan struct{}
}

func newFakeFrontier(jobs ...frontier.URLJob) *fakeFrontier {
	ch := make(chan frontier.URLJob, len(jobs)+1)
	for _, j := range jobs {
		ch <- j
	}
	return &fakeFrontier{jobs: ch, enqueueCall: make(chan struct{}, 10)}
}
func (f *fakeFrontier) Jobs() <-chan frontier.URLJob { return f.jobs }
func (f *fakeFrontier) Enqueue(parentDepth int, rawHTML []byte, baseURL string) {
	f.enqueueCall <- struct{}{}
}

func TestPool_FetchesPublishesAndEnqueuesLinks(t *testing.T) {
	page := &connector.RawPage{URL: "http://x.onion/", SourceType: "fake", HTML: []byte("<a href='http://y.onion/'>y</a>")}
	conn := &fakeConnector{page: page}
	d := newFakeDedup()
	pub := &fakePublisher{}
	f := newFakeFrontier(frontier.URLJob{URL: "http://x.onion/", Depth: 0})

	pool := New(1, conn, d, pub, f, 0)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pool.Run(ctx)

	if pub.count() != 1 {
		t.Fatalf("expected 1 published page, got %d", pub.count())
	}
	if !d.SeenRecently("http://x.onion/") {
		t.Errorf("expected URL to be marked seen")
	}
	select {
	case <-f.enqueueCall:
	default:
		t.Errorf("expected Enqueue to be called with discovered links")
	}
}

func TestPool_SkipsAlreadySeenURL(t *testing.T) {
	conn := &fakeConnector{page: &connector.RawPage{URL: "http://x.onion/"}}
	d := newFakeDedup()
	d.MarkSeen("http://x.onion/")
	pub := &fakePublisher{}
	f := newFakeFrontier(frontier.URLJob{URL: "http://x.onion/", Depth: 0})

	pool := New(1, conn, d, pub, f, 0)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pool.Run(ctx)

	if pub.count() != 0 {
		t.Fatalf("expected no publish for already-seen URL, got %d", pub.count())
	}
}

func TestPool_FetchErrorSkipsPublish(t *testing.T) {
	conn := &fakeConnector{err: errors.New("boom")}
	d := newFakeDedup()
	pub := &fakePublisher{}
	f := newFakeFrontier(frontier.URLJob{URL: "http://x.onion/", Depth: 0})

	pool := New(1, conn, d, pub, f, 0)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pool.Run(ctx)

	if pub.count() != 0 {
		t.Fatalf("expected no publish on fetch error, got %d", pub.count())
	}
}

func TestPool_GracefulShutdownOnContextCancel(t *testing.T) {
	conn := &fakeConnector{page: &connector.RawPage{URL: "http://x.onion/"}}
	d := newFakeDedup()
	pub := &fakePublisher{}
	f := newFakeFrontier() // no jobs — workers just wait on ctx.Done()

	pool := New(2, conn, d, pub, f, 0)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		pool.Run(ctx)
		close(done)
	}()

	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Run did not return after context cancellation")
	}
}
