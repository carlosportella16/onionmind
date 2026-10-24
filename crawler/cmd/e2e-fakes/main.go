// Command e2e-fakes stands in for everything external in the end-to-end smoke stack
// (e2e/docker-compose.e2e.yml): the Tor network (SOCKS5 on :1080), the .onion sites
// behind it (:8080) and Ollama (:11434). Test infrastructure, not shipped.
package main

import (
	"log/slog"
	"net/http"
	"net/url"
	"os"

	"github.com/carlosportella16/onionmind/crawler/internal/testfakes"
)

// The e2e site graph, keyed by host (each serves "/"): the seed links every other page.
// Terms here are what core/src/test/resources/features/smoke/e2e.feature searches for —
// change both together.
var pages = map[string]string{
	"semente-e2e.onion": `<html><body>
<p>Portal semente do teste ponta a ponta, onde o xilofonista mantém a lista de sites da rede para todos os visitantes.</p>
<a href="http://linkada-e2e.onion/">linkada</a>
<a href="http://conceito-e2e.onion/">conceito</a>
<a href="http://proibido-e2e.onion/">proibido</a>
<p>Sites listados em texto:<br>
  http://7hfnguqyq4qq7snghuqy6gxtwerrqfijw3kqf22ilhydoq5r6eqexluf.onion/<br></p>
</body></html>`,
	// Reached only through the plain-text address above (no <a>), like ahmia's /onions/.
	// Synthetic v3 host.
	"7hfnguqyq4qq7snghuqy6gxtwerrqfijw3kqf22ilhydoq5r6eqexluf.onion": `<html><body>
<p>Página achada só pelo endereço em texto, onde o ornitorrinco guarda o índice da rede.</p>
</body></html>`,
	"linkada-e2e.onion": `<html><body>
<p>Página descoberta por link, que fala do quetzal e da comunidade que mantém o portal desde o início.</p>
<p>Doações para 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa. Espelho em 2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion.</p>
</body></html>`,
	"conceito-e2e.onion": `<html><body>
<p>Mercado aceita moeda digital anônima e registra tudo num blockchain descentralizado para os compradores da rede.</p>
</body></html>`,
	// Host listed in e2e/denylist.txt: the core must quarantine it, never index it.
	"proibido-e2e.onion": `<html><body>
<p>Conteúdo de um host na lista de bloqueio, com o termo zircônio que nunca deve aparecer na busca do core.</p>
</body></html>`,
}

func main() {
	sites := testfakes.NewOnionSites()
	for host, html := range pages {
		// .onion services are plain http: Tor, not TLS, encrypts the hop.
		page := url.URL{Scheme: "http", Host: host, Path: "/"}
		sites.Set(page.String(), testfakes.Page{HTML: html})
	}

	go serve(":8080", sites)
	go serve(":11434", ollamaHandler())

	socks, err := testfakes.NewSOCKS5Server(":1080", "127.0.0.1:8080")
	if err != nil {
		slog.Error("socks5 listen failed", "err", err)
		os.Exit(1)
	}
	slog.Info("e2e fakes up", "socks5", socks.Addr(), "sites", ":8080", "ollama", ":11434")
	select {}
}

func serve(addr string, h http.Handler) {
	if err := http.ListenAndServe(addr, h); err != nil {
		slog.Error("listen failed", "addr", addr, "err", err)
		os.Exit(1)
	}
}
