package frontier

import (
	"crypto/md5"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/carlosportella16/onionmind/crawler/internal/denylist"
)

// Synthetic v3 hosts (random base32, not real services).
const (
	hostA = "mjbm2larmwjwcmi2ajum3jnwrkndr3fxo4klkoixqyh7bj6d3tdekf3l.onion"
	hostB = "qx2mrtvklarbrerzgipxipgfuxoybewiarztj66hya2mx3sc2gdeolqk.onion"
)

// drain closes the queue and returns everything enqueued.
func drain(f *Frontier) []string {
	close(f.queue)
	var urls []string
	for job := range f.Jobs() {
		urls = append(urls, job.URL)
	}
	return urls
}

func banning(t *testing.T, hosts ...string) *denylist.Denylist {
	t.Helper()
	var lines []byte
	for _, h := range hosts {
		sum := md5.Sum([]byte(h))
		lines = append(lines, hex.EncodeToString(sum[:])+"\n"...)
	}
	path := filepath.Join(t.TempDir(), "banned.md5")
	if err := os.WriteFile(path, lines, 0o600); err != nil {
		t.Fatal(err)
	}
	d, err := denylist.Load(path)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestEnqueue_SkipsBannedHosts(t *testing.T) {
	f := New(nil, 10, 3, banning(t, hostB))
	html := []byte(`<a href="http://` + hostA + `/">a</a> <a href="HTTPS://` + hostB + `/x">b</a>`)

	f.Enqueue(0, html, "http://base.onion/")

	if got := drain(f); len(got) != 1 || got[0] != "http://"+hostA+"/" {
		t.Fatalf("enqueued %v, want only %s", got, hostA)
	}
}

func TestEnqueue_OnlyOnionLinksAdded(t *testing.T) {
	f := New(nil, 10, 3, nil)
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
	f := New(nil, 10, 2, nil)
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
	f := New(nil, 1, 5, nil)
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
