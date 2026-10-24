package frontier

import (
	"log/slog"
	"net/url"
	"regexp"
	"strings"

	"golang.org/x/net/html"

	"github.com/carlosportella16/onionmind/crawler/internal/denylist"
)

// Frontier manages the queue of URLs to visit with depth control.
// It is not persistent — if the crawler restarts, it starts over from the seeds.
// Persistence (Redis sorted set) is a future-phase optimization.
type Frontier struct {
	queue           chan URLJob
	maxDepth        int
	maxLinksPerPage int
	deny            *denylist.Denylist
}

type URLJob struct {
	URL   string
	Depth int
}

// New queues the seeds as given; banned seeds are dropped by the worker before fetch.
// Discovered links to hosts on deny are never queued (nil deny bans nothing), and one page
// queues at most maxLinksPerPage links so a big listing cannot take over the queue.
func New(seeds []string, maxQueueSize, maxDepth, maxLinksPerPage int, deny *denylist.Denylist) *Frontier {
	f := &Frontier{
		queue:           make(chan URLJob, maxQueueSize),
		maxDepth:        maxDepth,
		maxLinksPerPage: maxLinksPerPage,
		deny:            deny,
	}
	for _, s := range seeds {
		f.queue <- URLJob{URL: s, Depth: 0}
	}
	return f
}

func (f *Frontier) Jobs() <-chan URLJob { return f.queue }

// Enqueue adds the first maxLinksPerPage distinct .onion links of a page, if within maxDepth.
func (f *Frontier) Enqueue(parentDepth int, rawHTML []byte, baseURL string) {
	if parentDepth >= f.maxDepth {
		return
	}
	links := extractLinks(rawHTML, baseURL)
	taken := 0
	for _, link := range links {
		if !isOnion(link) || f.deny.Banned(link) {
			continue
		}
		if taken == f.maxLinksPerPage {
			slog.Info("page links capped", "url", baseURL, "links", len(links), "max_links_per_page", f.maxLinksPerPage)
			return
		}
		taken++
		select {
		case f.queue <- URLJob{URL: link, Depth: parentDepth + 1}:
		default:
			// queue full — silently discard, never block the worker
		}
	}
}

// onionAddr matches a v3 onion address in plain text, with or without scheme and path
// (design D1) — e.g. ahmia's /onions/ listing, which has no <a> at all.
var onionAddr = regexp.MustCompile(`\b(https?://)?([a-z2-7]{56}\.onion)(/[^\s<"']*)?`)

// extractLinks returns the page's distinct links, in document order: every <a href>
// (resolved against baseURL) plus every v3 onion address in a text node.
func extractLinks(rawHTML []byte, baseURL string) []string {
	doc, err := html.Parse(strings.NewReader(string(rawHTML)))
	if err != nil {
		return nil
	}

	var links []string
	seen := map[string]bool{}
	add := func(u string) {
		if u != "" && !seen[u] {
			seen[u] = true
			links = append(links, u)
		}
	}
	var walk func(n *html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode && (n.Data == "script" || n.Data == "style") {
			return // code, not page text
		}
		for _, u := range nodeLinks(n, baseURL) {
			add(u)
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	return links
}

// nodeLinks returns the links n itself carries: its href if it is an <a>, or the onion
// addresses in it if it is a text node.
func nodeLinks(n *html.Node, baseURL string) []string {
	switch {
	case n.Type == html.ElementNode && n.Data == "a":
		if href, ok := attrValue(n, "href"); ok {
			return []string{resolveURL(href, baseURL)}
		}
	case n.Type == html.TextNode:
		var links []string
		for _, m := range onionAddr.FindAllStringSubmatch(n.Data, -1) {
			links = append(links, textURL(m[1], m[2], m[3]))
		}
		return links
	}
	return nil
}

// textURL builds the URL of an address found in text: no scheme means http, no path means "/".
func textURL(scheme, host, path string) string {
	if scheme == "" {
		scheme = "http://" // NOSONAR onion services speak plain HTTP; Tor encrypts the circuit end to end
	}
	if path == "" {
		path = "/"
	}
	return scheme + host + path
}

func attrValue(n *html.Node, key string) (string, bool) {
	for _, attr := range n.Attr {
		if attr.Key == key {
			return attr.Val, true
		}
	}
	return "", false
}

func resolveURL(href, base string) string {
	b, err := url.Parse(base)
	if err != nil {
		return ""
	}
	ref, err := url.Parse(href)
	if err != nil {
		return ""
	}
	return b.ResolveReference(ref).String()
}

func isOnion(u string) bool {
	parsed, err := url.Parse(u)
	if err != nil {
		return false
	}
	return strings.HasSuffix(parsed.Hostname(), ".onion")
}
