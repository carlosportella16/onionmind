package connector

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRawPage_JSONRoundTrip_HTMLIsPlainStringNotBase64(t *testing.T) {
	page := RawPage{
		URL:        "http://example.onion/",
		SourceType: "tor",
		HTML:       []byte("<html><body>hello world</body></html>"),
		FetchedAt:  "2026-08-22T00:00:00Z",
	}

	data, err := json.Marshal(page)
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}
	if !strings.Contains(string(data), "hello world") {
		t.Errorf("expected plain-text html in JSON payload, got: %s", data)
	}

	var decoded RawPage
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}
	if string(decoded.HTML) != string(page.HTML) {
		t.Errorf("HTML round-trip mismatch: got %q, want %q", decoded.HTML, page.HTML)
	}
	if decoded.URL != page.URL || decoded.SourceType != page.SourceType || decoded.FetchedAt != page.FetchedAt {
		t.Errorf("round-trip mismatch: got %+v, want %+v", decoded, page)
	}
}
