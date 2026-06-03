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

import java.io.IOException;
import java.util.List;

/**
 * Spring {@link ClientHttpRequestInterceptor} that enriches outbound OpenRouter API requests with:
 *
 * <ol>
 *   <li><b>Response caching</b> ({@code X-OpenRouter-Cache: true}) — OpenRouter saves the
 *       complete output of identical requests so repeat calls (e.g. retries with the same JD)
 *       are served instantly from cache at no cost.</li>
 *   <li><b>Model fallback array</b> — replaces the single {@code "model"} string in the
 *       request body with a {@code "models"} array. When the primary model returns a 503
 *       ("no healthy upstream"), OpenRouter automatically retries the next model in the list,
 *       eliminating the need for application-level retries for availability errors.</li>
 * </ol>
 *
 * <p>Only activates for requests targeting {@code openrouter.ai}; all other requests are
 * passed through unchanged.</p>
 */
public class OpenRouterInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterInterceptor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> fallbackModels;

    /**
     * @param fallbackModels ordered list of fallback model IDs tried after the primary fails.
     *                       The primary model is read from the original request body.
     */
    public OpenRouterInterceptor(List<String> fallbackModels) {
        this.fallbackModels = fallbackModels;
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

        // Rewrite the body to inject the "models" fallback array safely via Jackson
        byte[] modifiedBody = injectModels(body);

        return execution.execute(request, modifiedBody);
    }

    /**
     * Safely parses the JSON payload, replacing the singular "model" string with a "models" array
     * containing the primary model followed by the fallback options.
     * Uses Jackson for robust parsing, falling back to the original body if parsing fails.
     */
    private byte[] injectModels(byte[] bodyBytes) {
        if (fallbackModels.isEmpty() || bodyBytes == null || bodyBytes.length == 0) {
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

                for (String fallback : fallbackModels) {
                    if (!fallback.equals(primaryModel)) {
                        modelsArray.add(fallback);
                    }
                }

                log.debug("OpenRouter: injected fallback models {}", modelsArray);
                // Serialize the mutated JSON tree back to bytes
                return objectMapper.writeValueAsBytes(objectNode);
            }
        } catch (Exception e) {
            log.warn("Failed to parse or mutate JSON body for OpenRouter. Proceeding with original payload.", e);
        }

        // Fallback: return original body if it wasn't a JSON object with a "model" key, or if parsing failed
        return bodyBytes;
    }
}
