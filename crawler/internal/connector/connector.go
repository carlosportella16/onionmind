package connector

import (
	"context"
	"encoding/json"
)

// SourceConnector é a abstração genérica para fontes de conteúdo (ADR-004).
// TorConnector (Fase 1) é a primeira implementação.
type SourceConnector interface {
	ID() string
	Fetch(ctx context.Context, url string) (*RawPage, error)
}

type RawPage struct {
	URL        string `json:"url"`
	SourceType string `json:"source_type"`
	HTML       []byte `json:"html"`
	FetchedAt  string `json:"fetched_at"`
}

// rawPageJSON mirrors RawPage but carries HTML as a plain string. Go's
// encoding/json base64-encodes []byte fields by default, which the Java
// consumer does not expect (it wants raw HTML text in the "html" field).
type rawPageJSON struct {
	URL        string `json:"url"`
	SourceType string `json:"source_type"`
	HTML       string `json:"html"`
	FetchedAt  string `json:"fetched_at"`
}

func (r RawPage) MarshalJSON() ([]byte, error) {
	return json.Marshal(rawPageJSON{
		URL: r.URL, SourceType: r.SourceType, HTML: string(r.HTML), FetchedAt: r.FetchedAt,
	})
}

func (r *RawPage) UnmarshalJSON(data []byte) error {
	var aux rawPageJSON
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	r.URL, r.SourceType, r.HTML, r.FetchedAt = aux.URL, aux.SourceType, []byte(aux.HTML), aux.FetchedAt
	return nil
}
