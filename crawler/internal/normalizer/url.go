package normalizer

import (
	"net/url"
	"strings"
)

// Normalize canonicalizes a URL for dedup purposes: lowercase, no fragment,
// query params sorted, default port stripped, no trailing slash (except root).
func Normalize(raw string) string {
	u, err := url.Parse(strings.ToLower(strings.TrimSpace(raw)))
	if err != nil {
		return raw
	}
	u.Fragment = ""
	q := u.Query()
	u.RawQuery = q.Encode() // Encode sorts params by key
	if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
		u.Host = u.Hostname()
	}
	if u.Path != "/" {
		u.Path = strings.TrimRight(u.Path, "/")
	}
	return u.String()
}
