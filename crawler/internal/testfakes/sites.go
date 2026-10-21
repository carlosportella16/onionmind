package testfakes

import (
	"net/http"
	"strings"
	"sync"
)

// Page is one fake .onion page. Status defaults to 200. When PadToBytes is larger than
// the HTML, the body is padded with an HTML comment up to that size (oversized pages).
type Page struct {
	HTML       string
	Status     int
	PadToBytes int
}

// OnionSites serves fake .onion pages keyed by "http://<host><path>", routed by the Host
// header the client sent through the SOCKS5 proxy, and counts requests per page.
type OnionSites struct {
	mu    sync.Mutex
	pages map[string]Page
	hits  map[string]int
}

func NewOnionSites() *OnionSites {
	return &OnionSites{pages: map[string]Page{}, hits: map[string]int{}}
}

// Set declares (or replaces) the page served at url, e.g. "http://alfa.onion/".
func (o *OnionSites) Set(url string, p Page) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.pages[url] = p
}

// Hits returns how many requests reached url.
func (o *OnionSites) Hits(url string) int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.hits[url]
}

// Reset forgets every page and counter (between scenarios).
func (o *OnionSites) Reset() {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.pages, o.hits = map[string]Page{}, map[string]int{}
}

func (o *OnionSites) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	url := "http://" + r.Host + r.URL.Path
	o.mu.Lock()
	o.hits[url]++
	page, ok := o.pages[url]
	o.mu.Unlock()

	if !ok {
		http.NotFound(w, r)
		return
	}
	status := page.Status
	if status == 0 {
		status = http.StatusOK
	}
	body := page.HTML
	if pad := page.PadToBytes - len(body); pad > 0 {
		body += "<!--" + strings.Repeat("x", max(0, pad-7)) + "-->"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}
