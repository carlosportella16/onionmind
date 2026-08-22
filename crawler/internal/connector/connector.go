package connector

import "context"

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
