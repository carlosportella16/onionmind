package main

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func cosine(a, b []float64) float64 {
	var dot float64
	for i := range a {
		dot += a[i] * b[i]
	}
	return dot // unit vectors
}

func TestEmbedSeparatesConceptsAroundTheCoreMinScore(t *testing.T) {
	const minScore = 0.45 // core application.yml embedding.min-score
	query := embed("criptomoedas")
	page := embed(pages["conceito-e2e.onion"])

	if got := cosine(query, page); got <= minScore {
		t.Errorf("paraphrase should clear min-score, got %.3f", got)
	}
	if got := cosine(embed("receita de bolo de cenoura"), page); got >= minScore {
		t.Errorf("unrelated text should stay below min-score, got %.3f", got)
	}
	if got := cosine(query, query); math.Abs(got-1) > 1e-9 {
		t.Errorf("vectors must be unit length, got %.6f", got)
	}
	if got := len(embed("")); got != dimensions {
		t.Errorf("empty text vector has %d dims, want %d", got, dimensions)
	}
}

func TestOllamaHandlerSpeaksTheEmbedAndGenerateContracts(t *testing.T) {
	srv := httptest.NewServer(ollamaHandler())
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/api/embed", "application/json", strings.NewReader(`{"model":"m","input":"bitcoin"}`))
	if err != nil {
		t.Fatal(err)
	}
	var emb struct{ Embeddings [][]float64 }
	_ = json.NewDecoder(resp.Body).Decode(&emb)
	resp.Body.Close()
	if len(emb.Embeddings) != 1 || len(emb.Embeddings[0]) != dimensions {
		t.Fatalf("unexpected embed response %v", emb)
	}

	tasks := map[string]string{
		"Classify the page":                 "category",
		"Identify the predominant language": "language",
		"You summarize web pages":           "summary",
		"Extract the named entities":        "entities",
	}
	for system, field := range tasks {
		body := `{"model":"m","system":"` + system + `","prompt":"texto","stream":false}`
		resp, err := http.Post(srv.URL+"/api/generate", "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		var gen struct{ Response string }
		_ = json.NewDecoder(resp.Body).Decode(&gen)
		resp.Body.Close()

		var answer map[string]any
		if err := json.Unmarshal([]byte(gen.Response), &answer); err != nil {
			t.Fatalf("%s: response is not JSON: %q", system, gen.Response)
		}
		if _, ok := answer[field]; !ok || answer["confidence"] == nil {
			t.Errorf("%s: want %q and confidence, got %v", system, field, answer)
		}
	}

	resp, err = http.Post(srv.URL+"/api/generate", "application/json", strings.NewReader(`not json`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("malformed request should be 400, got %d", resp.StatusCode)
	}
}
