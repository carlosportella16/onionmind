package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP+JSON plumbing for the cloud adapters: raw {@code java.net.http.HttpClient} +
 * Jackson rather than a new client. Also the part every cloud provider shares: no key →
 * zero quota and a refusal; a 429/402 → the provider is parked for as long as the
 * ceiling it hit lasts (replace-ollama-with-gemini-deepseek D5).
 */
abstract class HttpAIProvider implements AIProvider {

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

    /** Which ceiling a 429 means. Per minute unless the provider says otherwise in the body. */
    protected ProviderException.Quota quotaOf429(String body) {
        return ProviderException.Quota.MINUTE;
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
            throw new ProviderException(error, quotaOf429(response.body()));
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
