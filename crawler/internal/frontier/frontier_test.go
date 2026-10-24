package frontier

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
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

func TestExtractLinks(t *testing.T) {
	cases := []struct {
		name string
		html string
		want []string
	}{
		{
			name: "<br> listing without anchors",
			html: "<html><body>\n  http://" + hostA + "/<br>\n  http://" + hostB + "/<br>\n</body></html>",
			want: []string{"http://" + hostA + "/", "http://" + hostB + "/"},
		},
		{
			name: "address without scheme or path becomes http root",
			html: "<p>Mirror at " + hostA + ". Also https://" + hostB + "/forum/t?id=1 here.</p>",
			want: []string{"http://" + hostA + "/", "https://" + hostB + "/forum/t?id=1"},
		},
		{
			name: "addresses in script, style and comments are ignored",
			html: "<script>var u='http://" + hostA + "/'</script><style>/* " + hostA + " */</style><!-- " + hostA + " --><p>" + hostB + "</p>",
			want: []string{"http://" + hostB + "/"},
		},
		{
			name: "href also present in text is not duplicated",
			html: `<a href="http://` + hostA + `/">http://` + hostA + `/</a> ` + hostA + ` <a href="/rel">rel</a>`,
			want: []string{"http://" + hostA + "/", "http://base.onion/rel"},
		},
		{
			name: "v2-length and over-long base32 runs are not addresses",
			html: "<p>abcdefgh234567ab.onion x" + hostA + "</p>",
			want: nil,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := extractLinks([]byte(c.html), "http://base.onion/"); !slices.Equal(got, c.want) {
				t.Errorf("extractLinks = %v, want %v", got, c.want)
			}
		})
	}
}

// The fixture mirrors the shape of ahmia's /onions/ (synthetic hosts): every line is a link.
func TestExtractLinks_AhmiaListingExcerpt(t *testing.T) {
	data, err := os.ReadFile("testdata/ahmia-onions-excerpt.html")
	if err != nil {
		t.Fatal(err)
	}
	lines := regexp.MustCompile(`http://[a-z2-7]{56}\.onion/<br>`).FindAll(data, -1)
	if len(lines) < 10 {
		t.Fatalf("fixture has %d listing lines, want 10-20", len(lines))
	}
	got := extractLinks(data, "http://ahmia.fi/onions/")
	if len(got) != len(lines) {
		t.Fatalf("extracted %d links from %d listing lines: %v", len(got), len(lines), got)
	}
	for i, line := range lines {
		if want := strings.TrimSuffix(string(line), "<br>"); got[i] != want {
			t.Errorf("link %d = %q, want %q", i, got[i], want)
		}
	}
}

func TestEnqueue_CapsLinksPerPage(t *testing.T) {
	// 5000 distinct v3 addresses in a <br> listing; only the first 2000 are queued.
	var page strings.Builder
	var want []string
	for i := range 5000 {
		host := fmt.Sprintf("%056d", i) // digits 0,1,8,9 aren't base32: map to letters
		host = strings.NewReplacer("0", "a", "1", "b", "8", "c", "9", "d").Replace(host) + ".onion"
		fmt.Fprintf(&page, "  http://%s/<br>\n", host)
		if i < 2000 {
			want = append(want, "http://"+host+"/")
		}
	}
	f := New(nil, 10000, 3, 2000, nil)

	f.Enqueue(0, []byte(page.String()), "http://ahmia.fi/onions/")

	if got := drain(f); !slices.Equal(got, want) {
		t.Fatalf("enqueued %d links, want the first %d", len(got), len(want))
	}
}

func TestEnqueue_SkipsBannedHosts(t *testing.T) {
	f := New(nil, 10, 3, 100, banning(t, hostB))
	html := []byte(`<a href="http://` + hostA + `/">a</a> <a href="HTTPS://` + hostB + `/x">b</a>`)

	f.Enqueue(0, html, "http://base.onion/")

	if got := drain(f); len(got) != 1 || got[0] != "http://"+hostA+"/" {
		t.Fatalf("enqueued %v, want only %s", got, hostA)
	}
}

func TestEnqueue_OnlyOnionLinksAdded(t *testing.T) {
	f := New(nil, 10, 3, 100, nil)
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
	f := New(nil, 10, 2, 100, nil)
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
	f := New(nil, 1, 5, 100, nil)
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
