package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared HTTP+JSON plumbing for the cloud adapters: raw {@code java.net.http.HttpClient} +
 * Jackson rather than a new client. Also the part every cloud provider shares: no key →
 * zero quota and a refusal; a 429/402 → the provider is parked for as long as the
 * ceiling it hit lasts (replace-ollama-with-gemini-deepseek D5). A 429 from a free-tier quota
 * parks until released: .onion text must never reach a free tier (local-onnx-embeddings D7).
 */
abstract class HttpAIProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpAIProvider.class);

    protected final ObjectMapper mapper = JsonMapper.builder().build();
    protected final Duration requestTimeout;
    protected final String apiKey;
    private final ProviderQuotaTracker quotaTracker;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    protected HttpAIProvider(Duration requestTimeout, String apiKey, ProviderQuotaTracker quotaTracker) {
        this.requestTimeout = requestTimeout;
        this.apiKey = apiKey;
        this.quotaTracker = quotaTracker;
    }

    @Override
    public final CompletionResponse complete(CompletionRequest request) {
        if (!hasKey()) {
            throw new ProviderException(id() + " api key not configured", false, false);
        }
        try {
            return call(request);
        } catch (ProviderException e) {
            if (e.rateLimited()) {
                if (e.exhausted() == ProviderException.Quota.FREE_TIER) {
                    log.error("AI provider {} answered from a FREE TIER quota — parked until released, "
                        + "no more .onion text goes to it: {}", id(), e.getMessage());
                }
                quotaTracker.park(id(), quotaTracker.quotaReturns(e.exhausted()));
            }
            throw e;
        }
    }

    @Override
    public ProviderQuota currentQuota() {
        return hasKey() ? quotaTracker.remaining(id()) : new ProviderQuota(0, 0);
    }

    /** The provider-specific request; only runs with a key configured. */
    protected abstract CompletionResponse call(CompletionRequest request);

    /**
     * Which ceiling a 429 means, from the quota ids Google lists in
     * {@code error.details[].violations[].quotaId}: a {@code FreeTier} id parks until released,
     * a per-day one until the quota day ends, anything else (DeepSeek sends none) a minute.
     */
    static ProviderException.Quota quotaOf429(List<String> quotaIds) {
        if (quotaIds.stream().anyMatch(q -> q.contains("FreeTier"))) {
            return ProviderException.Quota.FREE_TIER;
        }
        return quotaIds.stream().anyMatch(q -> q.contains("PerDay"))
            ? ProviderException.Quota.DAY : ProviderException.Quota.MINUTE;
    }

    private List<String> quotaIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            for (JsonNode detail : mapper.readTree(body).path("error").path("details")) {
                for (JsonNode violation : detail.path("violations")) {
                    String id = violation.path("quotaId").asString("");
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            // unparseable 429 body: no ids, the shortest park
        }
        return ids;
    }

    /** POSTs {@code body} as JSON, returns the parsed response tree, maps failures to {@link ProviderException}. */
    protected JsonNode postJson(String url, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json");
        headers.forEach(request::header);

        HttpResponse<String> response;
        try {
            String json = mapper.writeValueAsString(body);
            response = httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ProviderException(id() + " request failed", e);
        }

        int status = response.statusCode();
        String error = id() + " HTTP " + status + " - " + truncate(response.body());
        if (status == 429) {
            // The ids go before the body: past 200 chars it is cut, and they used to go with it.
            List<String> ids = quotaIds(response.body());
            throw new ProviderException(ids.isEmpty() ? error
                : id() + " HTTP 429 quotaId=" + String.join(",", ids) + " - " + truncate(response.body()),
                quotaOf429(ids));
        }
        if (status == 402) {
            throw new ProviderException(error, ProviderException.Quota.BALANCE);
        }
        if (status >= 400) {
            throw new ProviderException(error, status == 408 || status >= 500, false);
        }

        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new ProviderException(id() + " returned unparseable body", e);
        }
    }

    private boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
