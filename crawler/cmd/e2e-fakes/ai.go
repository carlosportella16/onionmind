package main

import (
	"encoding/json"
	"net/http"
	"strings"
)

// answer recognises the core's task from the fixed marker each DefaultAIOrchestrator prompt opens with.
func answer(prompt string) map[string]any {
	switch {
	case strings.Contains(prompt, "Extract the named entities"):
		// Named entities only; the e2e wallet/onion address come from the core's own patterns.
		return map[string]any{"entities": []any{}, "confidence": 0.9}
	case strings.Contains(prompt, "Classify the page"):
		return map[string]any{"category": "forum", "confidence": 0.9}
	case strings.Contains(prompt, "Identify the predominant language"):
		return map[string]any{"language": "pt", "confidence": 0.9}
	case strings.Contains(prompt, "Rewrite the question as a short English search query"):
		// The system prompt is one line; the question follows it.
		_, question, _ := strings.Cut(prompt, "\n")
		return map[string]any{"query": strings.TrimSpace(question), "confidence": 0.9}
	case strings.Contains(prompt, "Answer the question using only the sources"):
		if strings.Contains(prompt, `<source id="S1">`) {
			return map[string]any{"answer": "The indexed pages answer this [S1].", "citations": []string{"S1"}, "confidence": 0.9}
		}
		return map[string]any{"answer": "The sources do not answer the question.", "citations": []string{}, "confidence": 0.9}
	default:
		return map[string]any{"summary": "Automatic summary of the page.", "confidence": 0.9}
	}
}

// aiHandler fakes the generation endpoints the core calls: Gemini's generateContent (under
// /v1beta/models/) and DeepSeek's OpenAI-compatible /chat/completions. Embeddings are not faked:
// the core makes them with its local model (local-onnx-embeddings).
func aiHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1beta/models/{call}", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.PathValue("call"), ":generateContent") {
			http.NotFound(w, r)
			return
		}
		geminiGenerate(w, r)
	})
	mux.HandleFunc("POST /chat/completions", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Messages []struct {
				Content string `json:"content"`
			} `json:"messages"`
		}
		if !decode(w, r, &req) {
			return
		}
		var prompt string
		for _, m := range req.Messages {
			prompt += m.Content + "\n"
		}
		out, _ := json.Marshal(answer(prompt))
		writeJSON(w, map[string]any{"choices": []any{
			map[string]any{"message": map[string]any{"role": "assistant", "content": string(out)}},
		}})
	})
	return mux
}

type geminiPart struct {
	Text string `json:"text"`
}

type geminiContent struct {
	Parts []geminiPart `json:"parts"`
}

func (c geminiContent) text() string {
	var b strings.Builder
	for _, p := range c.Parts {
		b.WriteString(p.Text)
	}
	return b.String()
}

// decode reads the JSON request body into v, answering 400 when it is malformed.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}

func geminiGenerate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		SystemInstruction geminiContent   `json:"systemInstruction"`
		Contents          []geminiContent `json:"contents"`
	}
	if !decode(w, r, &req) {
		return
	}
	prompt := req.SystemInstruction.text()
	for _, c := range req.Contents {
		prompt += "\n" + c.text()
	}
	out, _ := json.Marshal(answer(prompt))
	writeJSON(w, map[string]any{"candidates": []any{
		map[string]any{"content": geminiContent{Parts: []geminiPart{{Text: string(out)}}}},
	}})
}
