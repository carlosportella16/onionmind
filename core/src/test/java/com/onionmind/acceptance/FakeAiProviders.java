package com.onionmind.acceptance;

import com.onionmind.ai.FakeHttpServer;
import com.onionmind.ai.FakeHttpServer.Reply;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The only fakes in the acceptance suite: the external model endpoints (design D4). Real
 * adapters, router, quota tracker, decorators and Qdrant talk to these over HTTP.
 *
 * <p>Generation answers are deterministic per task type. Embeddings are a bag of concepts:
 * terms from {@link #CONCEPTS} weigh heavily on their concept's axis, every other word lands
 * lightly on a hashed axis — so paraphrases of one concept are close, unrelated text is not.
 */
final class FakeAiProviders implements AutoCloseable {

    static final int DIMENSIONS = 64;

    private static final Map<String, List<String>> CONCEPTS = Map.of(
        "cripto", List.of("bitcoin", "blockchain", "criptomoeda", "criptomoedas", "monero", "moeda", "moedas"),
        "vazamento", List.of("vazamento", "vazamentos", "senhas", "credenciais", "dump"),
        "documentos", List.of("passaporte", "passaportes", "documentos", "identidade", "falsificados"));
    private static final List<String> AXES = List.copyOf(new java.util.TreeSet<>(CONCEPTS.keySet()));
    private static final double CONCEPT_WEIGHT = 3.0;
    private static final double WORD_WEIGHT = 0.3;
    private static final Pattern WORDS = Pattern.compile("\\p{L}+");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    final FakeHttpServer ollama = new FakeHttpServer();
    final FakeHttpServer groq = new FakeHttpServer();
    final FakeHttpServer gemini = new FakeHttpServer();

    /** What the fake model answers to a classification prompt; scenarios may override it. */
    volatile String categoryAnswer;

    FakeAiProviders() {
        reset();
        ollama.responder = (path, body) -> path.endsWith("/api/embed")
            ? json(Map.of("embeddings", List.of(toList(embed(field(body, "input"))))))
            : json(Map.of("response", answer(body, "ollama")));
        groq.responder = (path, body) -> json(Map.of("choices",
            List.of(Map.of("message", Map.of("content", answer(body, "groq"))))));
        gemini.responder = (path, body) -> json(Map.of("candidates",
            List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", answer(body, "gemini"))))))));
    }

    void reset() {
        categoryAnswer = "forum";
        for (FakeHttpServer s : List.of(ollama, groq, gemini)) {
            s.requestBodies.clear();
            s.requestPaths.clear();
            s.authHeaders.clear();
        }
    }

    /** Generation calls (not embeddings) the given fake received. */
    static long generationCalls(FakeHttpServer server) {
        return server.requestPaths.stream().filter(p -> !p.endsWith("/api/embed")).count();
    }

    /** Task type is recognised from the orchestrator's fixed system prompts (ASCII parts only). */
    private String answer(String requestBody, String provider) {
        Map<String, Object> payload;
        if (requestBody.contains("Classifique")) {
            payload = Map.of("category", categoryAnswer, "confidence", 0.9);
        } else if (requestBody.contains("Traduza")) {
            payload = Map.of("translation", "Tradução automática (" + provider + ") para português.",
                "detectedLanguage", "en", "confidence", 0.9);
        } else if (requestBody.contains("Identifique o idioma")) {
            payload = Map.of("language", requestBody.contains(" the ") ? "en" : "pt", "confidence", 0.9);
        } else {
            payload = Map.of("summary", "Resumo automático (" + provider + ") da página.", "confidence", 0.9);
        }
        return mapper.writeValueAsString(payload);
    }

    static float[] embed(String text) {
        double[] v = new double[DIMENSIONS];
        var matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            int axis = conceptAxis(word);
            if (axis >= 0) {
                v[axis] += CONCEPT_WEIGHT;
            } else {
                v[AXES.size() + Math.floorMod(word.hashCode(), DIMENSIONS - AXES.size())] += WORD_WEIGHT;
            }
        }
        double norm = 0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[DIMENSIONS];
        if (norm == 0) {
            out[DIMENSIONS - 1] = 1f; // empty text still needs a valid unit vector for cosine
            return out;
        }
        for (int i = 0; i < DIMENSIONS; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // both unit vectors
    }

    private static int conceptAxis(String word) {
        for (int i = 0; i < AXES.size(); i++) {
            if (CONCEPTS.get(AXES.get(i)).contains(word)) return i;
        }
        return -1;
    }

    private static List<Float> toList(float[] v) {
        Float[] boxed = new Float[v.length];
        for (int i = 0; i < v.length; i++) boxed[i] = v[i];
        return List.of(boxed);
    }

    private String field(String json, String name) {
        return mapper.readTree(json).path(name).asString("");
    }

    private Reply json(Object body) {
        return new Reply(200, mapper.writeValueAsString(body));
    }

    @Override
    public void close() {
        ollama.close();
        groq.close();
        gemini.close();
    }
}
