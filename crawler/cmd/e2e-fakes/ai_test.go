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

func post(t *testing.T, url, body string, out any) int {
	t.Helper()
	resp, err := http.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func TestGeminiEmbedEndpointsStripTheRolePrefixAndKeepOrder(t *testing.T) {
	srv := httptest.NewServer(aiHandler())
	defer srv.Close()

	var batch struct{ Embeddings []struct{ Values []float64 } }
	post(t, srv.URL+"/v1beta/models/gemini-embedding-2:batchEmbedContents", `{"requests":[
		{"model":"models/gemini-embedding-2","content":{"parts":[{"text":"task: search result | query: criptomoedas"}]}},
		{"model":"models/gemini-embedding-2","content":{"parts":[{"text":"title: http://x.onion/ | text: senhas"}]}}]}`, &batch)
	if len(batch.Embeddings) != 2 || len(batch.Embeddings[0].Values) != dimensions {
		t.Fatalf("unexpected batch response %v", batch)
	}
	if got := cosine(batch.Embeddings[0].Values, embed("criptomoedas")); math.Abs(got-1) > 1e-9 {
		t.Errorf("query prefix should be ignored, cosine to the bare text = %.6f", got)
	}
	if got := cosine(batch.Embeddings[1].Values, embed("senhas")); math.Abs(got-1) > 1e-9 {
		t.Errorf("document prefix should be ignored, cosine to the bare text = %.6f", got)
	}

	var single struct{ Embedding struct{ Values []float64 } }
	post(t, srv.URL+"/v1beta/models/gemini-embedding-2:embedContent",
		`{"content":{"parts":[{"text":"task: search result | query: bitcoin"}]}}`, &single)
	if len(single.Embedding.Values) != dimensions {
		t.Fatalf("unexpected embedContent response %v", single)
	}
}

func TestGenerationEndpointsAnswerEachTaskInJSON(t *testing.T) {
	srv := httptest.NewServer(aiHandler())
	defer srv.Close()

	tasks := map[string]string{
		"Classify the page":                 "category",
		"Identify the predominant language": "language",
		"You summarize web pages":           "summary",
		"Extract the named entities":        "entities",
	}
	for system, field := range tasks {
		var gen struct {
			Candidates []struct{ Content geminiContent }
		}
		post(t, srv.URL+"/v1beta/models/gemini-2.5-flash-lite:generateContent",
			`{"systemInstruction":{"parts":[{"text":"`+system+`"}]},"contents":[{"role":"user","parts":[{"text":"texto"}]}]}`, &gen)
		var chat struct {
			Choices []struct{ Message struct{ Content string } }
		}
		post(t, srv.URL+"/chat/completions",
			`{"model":"deepseek-flash","messages":[{"role":"system","content":"`+system+`"},{"role":"user","content":"texto"}]}`, &chat)
		if len(gen.Candidates) != 1 || len(chat.Choices) != 1 {
			t.Fatalf("%s: unexpected responses %v / %v", system, gen, chat)
		}

		for provider, text := range map[string]string{
			"gemini": gen.Candidates[0].Content.text(), "deepseek": chat.Choices[0].Message.Content,
		} {
			var answer map[string]any
			if err := json.Unmarshal([]byte(text), &answer); err != nil {
				t.Fatalf("%s/%s: response is not JSON: %q", provider, system, text)
			}
			if _, ok := answer[field]; !ok || answer["confidence"] == nil {
				t.Errorf("%s/%s: want %q and confidence, got %v", provider, system, field, answer)
			}
		}
	}

	if code := post(t, srv.URL+"/chat/completions", `not json`, nil); code != http.StatusBadRequest {
		t.Errorf("malformed request should be 400, got %d", code)
	}
	if code := post(t, srv.URL+"/v1beta/models/x:countTokens", `{}`, nil); code != http.StatusNotFound {
		t.Errorf("unknown Gemini call should be 404, got %d", code)
	}
}
