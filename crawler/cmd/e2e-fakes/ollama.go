package main

import (
	"encoding/json"
	"hash/fnv"
	"math"
	"net/http"
	"regexp"
	"strings"
)

// dimensions must match the core's qdrant.vector-size in e2e/docker-compose.e2e.yml.
const dimensions = 64

// Terms of one concept land on that concept's axis with a heavy weight; every other word
// lands lightly on a hashed axis — paraphrases of a concept are close, unrelated text is not.
// Same idea as core's FakeAiProviders (acceptance suite), on purpose kept tiny on both sides.
var concepts = [][]string{
	{"bitcoin", "blockchain", "criptomoeda", "criptomoedas", "monero", "moeda", "moedas"},
	{"vazamento", "vazamentos", "senhas", "credenciais", "dump"},
	{"passaporte", "passaportes", "documentos", "identidade", "falsificados"},
}

var words = regexp.MustCompile(`\p{L}+`)

const conceptWeight, wordWeight = 3.0, 0.3

func embed(text string) []float64 {
	v := make([]float64, dimensions)
	for _, w := range words.FindAllString(strings.ToLower(text), -1) {
		if axis := conceptAxis(w); axis >= 0 {
			v[axis] += conceptWeight
			continue
		}
		h := fnv.New32a()
		_, _ = h.Write([]byte(w))
		v[len(concepts)+int(h.Sum32()%uint32(dimensions-len(concepts)))] += wordWeight
	}
	var norm float64
	for _, x := range v {
		norm += x * x
	}
	if norm == 0 {
		v[dimensions-1] = 1 // empty text still needs a valid unit vector for cosine
		return v
	}
	norm = math.Sqrt(norm)
	for i := range v {
		v[i] /= norm
	}
	return v
}

func conceptAxis(word string) int {
	for i, terms := range concepts {
		for _, t := range terms {
			if t == word {
				return i
			}
		}
	}
	return -1
}

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
	default:
		return map[string]any{"summary": "Automatic summary of the page.", "confidence": 0.9}
	}
}

// ollamaHandler fakes the two Ollama endpoints the core calls: /api/embed and /api/generate.
func ollamaHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/embed", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Input string `json:"input"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, map[string]any{"embeddings": [][]float64{embed(req.Input)}})
	})
	mux.HandleFunc("POST /api/generate", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			System string `json:"system"`
			Prompt string `json:"prompt"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		out, _ := json.Marshal(answer(req.System + "\n" + req.Prompt))
		writeJSON(w, map[string]any{"response": string(out)})
	})
	return mux
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}
