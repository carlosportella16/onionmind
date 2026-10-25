package main

import (
	"encoding/json"
	"hash/fnv"
	"math"
	"net/http"
	"regexp"
	"strings"
)

// dimensions must match the core's embedding.dimensions in e2e/docker-compose.e2e.yml.
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

// rolePrefix is what the core's GeminiEmbedder puts in front of every text (query or
// document chunk); it is the same on every text, so the fake embeds what follows it.
var rolePrefix = regexp.MustCompile(`^(task: [^|]*\| query: |title: [^|]*\| text: )`)

// aiHandler fakes the model endpoints the core calls: Gemini's generateContent,
// batchEmbedContents and embedContent (under /v1beta/models/), and DeepSeek's OpenAI-compatible
// /chat/completions.
func aiHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1beta/models/{call}", func(w http.ResponseWriter, r *http.Request) {
		call := r.PathValue("call")
		switch {
		case strings.HasSuffix(call, ":generateContent"):
			geminiGenerate(w, r)
		case strings.HasSuffix(call, ":batchEmbedContents"):
			geminiBatchEmbed(w, r)
		case strings.HasSuffix(call, ":embedContent"):
			geminiEmbed(w, r)
		default:
			http.NotFound(w, r)
		}
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

func geminiBatchEmbed(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Requests []struct {
			Content geminiContent `json:"content"`
		} `json:"requests"`
	}
	if !decode(w, r, &req) {
		return
	}
	embeddings := make([]any, len(req.Requests))
	for i, e := range req.Requests {
		embeddings[i] = map[string]any{"values": embed(rolePrefix.ReplaceAllString(e.Content.text(), ""))}
	}
	writeJSON(w, map[string]any{"embeddings": embeddings})
}

func geminiEmbed(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Content geminiContent `json:"content"`
	}
	if !decode(w, r, &req) {
		return
	}
	writeJSON(w, map[string]any{"embedding": map[string]any{
		"values": embed(rolePrefix.ReplaceAllString(req.Content.text(), "")),
	}})
}
