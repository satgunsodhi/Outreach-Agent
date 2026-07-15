package com.outreach.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/**
 * Spring {@link ClientHttpRequestInterceptor} that enriches outbound OpenRouter
 * API requests with:
 *
 * <ol>
 * <li><b>Response caching</b> ({@code X-OpenRouter-Cache: true}) — OpenRouter
 * saves the
 * complete output of identical requests so repeat calls (e.g. retries with the
 * same JD)
 * are served instantly from cache at no cost.</li>
 * <li><b>Model fallback array</b> — replaces the single {@code "model"} string
 * in the
 * request body with a {@code "models"} array. When the primary model returns a
 * 503
 * ("no healthy upstream"), OpenRouter automatically retries the next model in
 * the list,
 * eliminating the need for application-level retries for availability
 * errors.</li>
 * </ol>
 *
 * <p>
 * Only activates for requests targeting {@code openrouter.ai}; all other
 * requests are
 * passed through unchanged.
 * </p>
 */
public class OpenRouterInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterInterceptor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Supplier<List<String>> fallbackModelsSupplier;

    /**
     * @param fallbackModelsSupplier supplier for the ordered list of fallback model
     *                               IDs
     *                               tried after the primary fails.
     *                               The primary model is read from the original
     *                               request body.
     */
    public OpenRouterInterceptor(Supplier<List<String>> fallbackModelsSupplier) {
        this.fallbackModelsSupplier = fallbackModelsSupplier;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        // Only intercept OpenRouter calls
        String host = request.getURI().getHost();
        if (host == null || !host.contains("openrouter.ai")) {
            return execution.execute(request, body);
        }

        // Add the response-caching header
        request.getHeaders().set("X-OpenRouter-Cache", "true");

        // Add Identity Headers to bypass shared IP rate-limiting (e.g., GitHub Actions)
        String repoUrl = System.getenv("GITHUB_REPOSITORY") != null
                ? "https://github.com/" + System.getenv("GITHUB_REPOSITORY")
                : "https://github.com/yourusername/yourrepo";
        request.getHeaders().set("HTTP-Referer", repoUrl);
        request.getHeaders().set("X-Title", "Outreach Agent");

        // Rewrite the body to inject the "models" fallback array safely via Jackson
        byte[] modifiedBody = injectModels(body);

        ClientHttpResponse response;
        try {
            response = execution.execute(request, modifiedBody);
        } catch (IOException e) {
            log.warn("OpenRouter request failed with IOException: {}. Attempting client-side fallback...", e.getMessage());
            response = executeWithFallback(request, modifiedBody, execution);
            if (response == null) {
                throw e; // Rethrow if all fallback models failed
            }
            return response;
        }

        int status = response.getStatusCode().value();
        // Fallback for Rate Limit (429) or Server/Gateway Errors (502/503/504)
        if ((status == 429 || status == 503 || status == 502 || status == 504 || status == 408) && modifiedBody != null) {
            log.warn("OpenRouter API returned error status {}. Triggering client-side fallback failover...", status);
            ClientHttpResponse fallbackResponse = executeWithFallback(request, modifiedBody, execution);
            if (fallbackResponse != null) {
                response.close();
                return fallbackResponse;
            }
        }

        // Log clear API errors (4xx/5xx) without consuming the stream.
        // We buffer the body so LangChain4j can still read it for its own error handling.
        if (response.getStatusCode().isError()) {
            byte[] bodyBytes = response.getBody().readAllBytes();
            String bodyStr = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.error("OpenRouter API error (Status {}): {}", response.getStatusCode().value(), bodyStr);
            // Wrap back so downstream (LangChain4j RetryUtils) can still read the body
            return new BufferedErrorResponse(response, bodyBytes);
        }

        return response;
    }

    /**
     * Safely parses the JSON payload, replacing the singular "model" string with a
     * "models" array
     * containing the primary model followed by the fallback options.
     * Uses Jackson for robust parsing, falling back to the original body if parsing
     * fails.
     */
    private byte[] injectModels(byte[] bodyBytes) {
        List<String> fallbackModels = fallbackModelsSupplier.get();
        if (fallbackModels == null || fallbackModels.isEmpty() || bodyBytes == null || bodyBytes.length == 0) {
            return bodyBytes;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(bodyBytes);
            if (rootNode.isObject() && rootNode.has("model")) {
                ObjectNode objectNode = (ObjectNode) rootNode;
                String primaryModel = objectNode.get("model").asText();

                // Mutate: Remove "model" string, inject "models" array
                objectNode.remove("model");
                ArrayNode modelsArray = objectNode.putArray("models");
                modelsArray.add(primaryModel);

                // OpenRouter caps the 'models' array at a maximum of 3 items
                int added = 1;
                for (String fallback : fallbackModels) {
                    if (added >= 3) {
                        break;
                    }
                    if (!fallback.equals(primaryModel)) {
                        modelsArray.add(fallback);
                        added++;
                    }
                }

                log.debug("OpenRouter: injected fallback models {}", modelsArray);
                // Serialize the mutated JSON tree back to bytes
                return objectMapper.writeValueAsBytes(objectNode);
            }
        } catch (Exception e) {
            log.warn("Failed to parse or mutate JSON body for OpenRouter. Proceeding with original payload.", e);
        }

        // Fallback: return original body if it wasn't a JSON object with a "model" key,
        // or if parsing failed
        return bodyBytes;
    }

    private ClientHttpResponse executeWithFallback(HttpRequest request, byte[] bodyBytes, ClientHttpRequestExecution execution) {
        byte[] currentBody = bodyBytes;
        while (true) {
            byte[] nextBody = stripFirstModel(currentBody);
            if (nextBody == null) {
                log.warn("No more fallback models left to try.");
                return null;
            }
            currentBody = nextBody;
            try {
                ClientHttpResponse response = execution.execute(request, currentBody);
                int status = response.getStatusCode().value();
                if (status == 200) {
                    log.info("Client-side fallback succeeded with status 200.");
                    return response;
                }
                log.warn("Client-side fallback request returned status: {}", status);
                response.close();
            } catch (IOException e) {
                log.warn("Client-side fallback request failed with IOException: {}", e.getMessage());
            }
        }
    }

    private byte[] stripFirstModel(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(bodyBytes);
            if (rootNode.isObject() && rootNode.has("models")) {
                ObjectNode objectNode = (ObjectNode) rootNode;
                JsonNode modelsNode = objectNode.get("models");
                if (modelsNode.isArray() && modelsNode.size() > 1) {
                    ArrayNode modelsArray = (ArrayNode) modelsNode;
                    String failedModel = modelsArray.remove(0).asText();
                    log.info("Client-side fallback: removed failed/rate-limited model '{}'. Next model: '{}'",
                            failedModel, modelsArray.get(0).asText());
                    return objectMapper.writeValueAsBytes(objectNode);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse or mutate JSON body for client-side fallback: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Wraps a consumed error response body back into a readable
     * {@link ClientHttpResponse},
     * so that LangChain4j's RetryUtils can still inspect the body for its own
     * error-mapping.
     */
    private static class BufferedErrorResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedErrorResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
