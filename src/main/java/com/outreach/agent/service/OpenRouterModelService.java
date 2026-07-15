package com.outreach.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.outreach.agent.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service to dynamically fetch and cache the active free models from OpenRouter.
 * Helps prevent hardcoded model errors (404/503) when OpenRouter updates its free tier catalog.
 */
@Service
public class OpenRouterModelService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterModelService.class);
    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    private final LlmProperties llmProperties;
    private final List<String> cachedFreeModels = new CopyOnWriteArrayList<>();

    public OpenRouterModelService(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @PostConstruct
    public void init() {
        // Fetch free models asynchronously at startup to avoid blocking main thread
        CompletableFuture.runAsync(this::fetchFreeModels);
    }

    /**
     * Periodically updates the cached list of free models (every 12 hours).
     * The first run is scheduled 12 hours after startup since the initial fetch is done in init().
     */
    @Scheduled(fixedDelay = 43200000, initialDelay = 43200000)
    public void scheduledFetch() {
        fetchFreeModels();
    }

    /**
     * Performs the network request to OpenRouter to fetch active models and filter by free tier pricing.
     */
    public synchronized void fetchFreeModels() {
        try {
            log.info("Fetching active free models from OpenRouter endpoint: {}", OPENROUTER_MODELS_URL);
            
            RestClient restClient = RestClient.builder()
                    .defaultHeader("Accept", "application/json")
                    .build();

            JsonNode response = restClient.get()
                    .uri(OPENROUTER_MODELS_URL)
                    .retrieve()
                    .body(JsonNode.class);

            if (response != null && response.has("data")) {
                JsonNode dataNode = response.get("data");
                if (dataNode.isArray()) {
                    List<String> freeModels = new ArrayList<>();
                    for (JsonNode modelNode : dataNode) {
                        String id = modelNode.path("id").asText();
                        JsonNode pricingNode = modelNode.path("pricing");
                        
                        if (!pricingNode.isMissingNode()) {
                            double promptPrice = pricingNode.path("prompt").asDouble();
                            double completionPrice = pricingNode.path("completion").asDouble();
                            
                            // A model is free if both prompt and completion prices are 0
                            if (promptPrice == 0.0 && completionPrice == 0.0) {
                                freeModels.add(id);
                            }
                        }
                    }
                    
                    if (!freeModels.isEmpty()) {
                        cachedFreeModels.clear();
                        cachedFreeModels.addAll(freeModels);
                        log.info("Successfully fetched and cached {} free models from OpenRouter: {}", 
                                freeModels.size(), cachedFreeModels);
                        return;
                    }
                }
            }
            log.warn("OpenRouter API returned empty or invalid data. Retaining existing fallback list.");
        } catch (Exception e) {
            log.error("Failed to fetch free models from OpenRouter: {}. Using configured fallback list.", e.getMessage(), e);
        }
    }

    /**
     * Returns the list of fallback models.
     * Uses dynamically cached free models if available, otherwise falls back to configured values in LlmProperties.
     */
    public List<String> getFallbackModels() {
        if (cachedFreeModels.isEmpty()) {
            log.debug("No cached free models available. Triggering fetch now for current execution.");
            fetchFreeModels(); // Fetch synchronously if empty
            
            if (!cachedFreeModels.isEmpty()) {
                return new ArrayList<>(cachedFreeModels);
            }
            
            log.debug("Still no cached free models available after fetch. Using configured fallback models from properties.");
            return llmProperties.getFallbackModels();
        }
        return new ArrayList<>(cachedFreeModels);
    }

    /**
     * Returns the primary model name.
     * If configured in LlmProperties, uses that.
     * Otherwise, fetches and uses the first dynamically cached free model.
     */
    public String getPrimaryModel() {
        String configured = llmProperties.getOpenRouterModelName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        
        if (cachedFreeModels.isEmpty()) {
            log.info("No cached free models available. Triggering fetch now for primary model.");
            fetchFreeModels();
        }
        
        if (!cachedFreeModels.isEmpty()) {
            String primary = cachedFreeModels.get(0);
            log.info("Using dynamically determined primary model: {}", primary);
            return primary;
        }
        
        throw new IllegalStateException("No primary model configured in properties, and no free models could be fetched from OpenRouter.");
    }
}
