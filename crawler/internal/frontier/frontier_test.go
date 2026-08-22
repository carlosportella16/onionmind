package frontier

import (
	"testing"
	"time"
)

func TestEnqueue_OnlyOnionLinksAdded(t *testing.T) {
	f := New(nil, 10, 3)
	html := []byte(`<html><body>
		<a href="http://other.onion/page">onion</a>
		<a href="https://clearnet.com">clearnet</a>
	</body></html>`)

	f.Enqueue(0, html, "http://base.onion/")

	var jobs []URLJob
	close(f.queue)
	for job := range f.Jobs() {
		jobs = append(jobs, job)
	}

	if len(jobs) != 1 {
		t.Fatalf("expected 1 job enqueued, got %d: %v", len(jobs), jobs)
	}
	if jobs[0].URL != "http://other.onion/page" {
		t.Errorf("expected other.onion link, got %q", jobs[0].URL)
	}
	if jobs[0].Depth != 1 {
		t.Errorf("expected depth 1, got %d", jobs[0].Depth)
	}
}

func TestEnqueue_RespectsMaxDepth(t *testing.T) {
	f := New(nil, 10, 2)
	html := []byte(`<a href="http://deep.onion/page">deep</a>`)

	// parentDepth already at maxDepth — link must not be enqueued.
	f.Enqueue(2, html, "http://base.onion/")

	select {
	case job := <-f.Jobs():
		t.Fatalf("expected no job enqueued past max depth, got %v", job)
	default:
	}
}

func TestEnqueue_FullQueueDoesNotBlock(t *testing.T) {
	f := New(nil, 1, 5)
	f.queue <- URLJob{URL: "http://filler.onion/", Depth: 0} // fill the only slot

	html := []byte(`<a href="http://other.onion/page">onion</a>`)

	done := make(chan struct{})
	go func() {
		f.Enqueue(0, html, "http://base.onion/")
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Enqueue blocked on a full queue")
	}
}
