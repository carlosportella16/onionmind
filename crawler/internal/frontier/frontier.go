package frontier

import (
	"net/url"
	"strings"

	"golang.org/x/net/html"
)

// Frontier manages the queue of URLs to visit with depth control.
// It is not persistent — if the crawler restarts, it starts over from the seeds.
// Persistence (Redis sorted set) is a future-phase optimization.
type Frontier struct {
	queue    chan URLJob
	maxDepth int
}

type URLJob struct {
	URL   string
	Depth int
}

func New(seeds []string, maxQueueSize, maxDepth int) *Frontier {
	f := &Frontier{
		queue:    make(chan URLJob, maxQueueSize),
		maxDepth: maxDepth,
	}
	for _, s := range seeds {
		f.queue <- URLJob{URL: s, Depth: 0}
	}
	return f
}

func (f *Frontier) Jobs() <-chan URLJob { return f.queue }

// Enqueue adds URLs discovered within a page, if within maxDepth.
func (f *Frontier) Enqueue(parentDepth int, rawHTML []byte, baseURL string) {
	if parentDepth >= f.maxDepth {
		return
	}
	links := extractLinks(rawHTML, baseURL)
	for _, link := range links {
		if !isOnion(link) {
			continue
		}
		select {
		case f.queue <- URLJob{URL: link, Depth: parentDepth + 1}:
		default:
			// queue full — silently discard, never block the worker
		}
	}
}

func extractLinks(rawHTML []byte, baseURL string) []string {
	var links []string
	doc, err := html.Parse(strings.NewReader(string(rawHTML)))
	if err != nil {
		return links
	}
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode && n.Data == "a" {
			for _, attr := range n.Attr {
				if attr.Key == "href" {
					resolved := resolveURL(attr.Val, baseURL)
					if resolved != "" {
						links = append(links, resolved)
					}
				}
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	return links
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
