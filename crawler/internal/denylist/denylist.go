// Package denylist keeps the crawler away from known-banned .onion hosts. The list is the
// format of ahmia's /banned/: one hex MD5 per line, each the hash of a bare onion domain
// ("<56 base32>.onion": no scheme, subdomain, port or path — ahmia-site
// BannedDomainListView.get_banned_domains + validators.extract_domain_from_url).
package denylist

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strings"
)

// Denylist is read-only after Load, so it is safe for concurrent use. A nil *Denylist bans nothing.
type Denylist struct {
	hashes map[string]struct{}
}

// Load reads the hash file at path. Blank lines are skipped; any other line that is not a
// 32-char hex MD5 is dropped and counted in the log. A missing file is an error.
func Load(path string) (*Denylist, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("load host denylist: %w", err)
	}
	d := &Denylist{hashes: map[string]struct{}{}}
	invalid := 0
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.ToLower(strings.TrimSpace(line))
		if line == "" {
			continue
		}
		if _, err := hex.DecodeString(line); err != nil || len(line) != 2*md5.Size {
			invalid++
			continue
		}
		d.hashes[line] = struct{}{}
	}
	slog.Info("host denylist loaded", "path", path, "hosts", len(d.hashes), "invalid_lines", invalid)
	return d, nil
}

// Len is the number of banned host hashes.
func (d *Denylist) Len() int {
	if d == nil {
		return 0
	}
	return len(d.hashes)
}

// Banned reports whether rawURL's host is on the list, whatever its case, scheme, port,
// path or subdomain (Tor ignores subdomains of an onion address, so www.<x>.onion is <x>.onion).
func (d *Denylist) Banned(rawURL string) bool {
	if d.Len() == 0 {
		return false
	}
	host := domain(rawURL)
	if host == "" {
		return false
	}
	// MD5 because that is the format ahmia publishes the list in: a lookup key, not a security control.
	sum := md5.Sum([]byte(host)) // NOSONAR
	_, ok := d.hashes[hex.EncodeToString(sum[:])]
	return ok
}

// domain normalizes rawURL to the domain ahmia hashes: lowercase, no scheme, port or path,
// and for .onion only the last two labels.
func domain(rawURL string) string {
	raw := strings.TrimSpace(rawURL)
	if !strings.Contains(raw, "://") {
		raw = "//" + raw // network-path reference, so url.Parse reads a bare host as the host
	}
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if labels := strings.Split(host, "."); len(labels) > 2 && labels[len(labels)-1] == "onion" {
		host = strings.Join(labels[len(labels)-2:], ".")
	}
	return host
}
