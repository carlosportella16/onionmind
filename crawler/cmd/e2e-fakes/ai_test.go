package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

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

func TestGenerationEndpointsAnswerEachTaskInJSON(t *testing.T) {
	srv := httptest.NewServer(aiHandler())
	defer srv.Close()

	tasks := map[string]string{
		"Classify the page":                                    "category",
		"Identify the predominant language":                    "language",
		"You summarize web pages":                              "summary",
		"Extract the named entities":                           "entities",
		"Rewrite the question as a short English search query": "query",
		"Answer the question using only the sources":           "answer",
	}
	for system, field := range tasks {
		var gen struct {
			Candidates []struct{ Content geminiContent }
		}
		post(t, srv.URL+"/v1beta/models/gemini-3.5-flash-lite:generateContent",
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
	for _, call := range []string{"x:countTokens", "gemini-embedding-2:batchEmbedContents"} {
		if code := post(t, srv.URL+"/v1beta/models/"+call, `{}`, nil); code != http.StatusNotFound {
			t.Errorf("%s should be 404 (embeddings are local now), got %d", call, code)
		}
	}
}

func TestChatAnswerCitesTheFirstSourceOnlyWhenOneWasSent(t *testing.T) {
	const system = "Answer the question using only the sources"
	cited := answer(system + "\nQuestion: q\n\n<source id=\"S1\">\nxilofonista\n</source>\n")
	if c := cited["citations"].([]string); len(c) != 1 || c[0] != "S1" {
		t.Errorf("want [S1], got %v", cited["citations"])
	}
	if c := answer(system + "\nQuestion: q\n")["citations"].([]string); len(c) != 0 {
		t.Errorf("no source should cite nothing, got %v", c)
	}
	if q := answer("Rewrite the question as a short English search query\n criptomoedas \n")["query"]; q != "criptomoedas" {
		t.Errorf("rewrite should echo the question, got %q", q)
	}
}
