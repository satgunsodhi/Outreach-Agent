package com.outreach.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("openrouter")
public class OpenRouterLlmConfig {

    private final LlmProperties llmProperties;

    public OpenRouterLlmConfig(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @Value("${logging.level.com.outreach.agent:INFO}")
    private String logLevel;

    /**
     * Registers the {@link OpenRouterInterceptor} as a Spring {@link RestClientCustomizer}.
     * LangChain4j's OpenAI integration (v1.15.0) uses Spring's RestClient internally, so this
     * customizer is automatically picked up and applied to every outbound LLM request.
     *
     * <p>The interceptor:</p>
     * <ol>
     *   <li>Adds {@code X-OpenRouter-Cache: true} for server-side response caching.</li>
     *   <li>Rewrites {@code "model":"x"} → {@code "models":["x","fallback1",...]} so
     *       OpenRouter automatically fails over to a healthy model on 503 errors.</li>
     * </ol>
     */
    @Bean
    public RestClientCustomizer openRouterRestClientCustomizer() {
        return restClientBuilder -> restClientBuilder
                .requestInterceptor(new OpenRouterInterceptor(llmProperties.getFallbackModels()));
    }

    private ChatModel createChatModelWithFallbacks(String primaryModel, double temperature, int maxTokens) {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        java.util.List<ChatModel> models = new java.util.ArrayList<>();
        
        // Add primary model
        models.add(OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(primaryModel)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .logRequests(isTrace)
                .logResponses(isTrace)
                .build());

        // Add fallback models
        for (String fallback : llmProperties.getFallbackModels()) {
            if (!fallback.equals(primaryModel)) {
                models.add(OpenAiChatModel.builder()
                        .baseUrl("https://openrouter.ai/api/v1")
                        .apiKey(llmProperties.getOpenRouterApiKey())
                        .modelName(fallback)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .logRequests(isTrace)
                        .logResponses(isTrace)
                        .build());
            }
        }

        // Return a proxy that tries each model in order with exponential backoff for 429/503 errors
        return (ChatModel) java.lang.reflect.Proxy.newProxyInstance(
                ChatModel.class.getClassLoader(),
                new Class<?>[]{ChatModel.class},
                (proxy, method, args) -> {
                    Exception lastException = null;
                    for (int i = 0; i < models.size(); i++) {
                        ChatModel model = models.get(i);
                        int maxRetries = 3;
                        long backoffMs = 5000;
                        for (int attempt = 1; attempt <= maxRetries; attempt++) {
                            try {
                                return method.invoke(model, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                // B9: getCause() can be null if the InvocationTargetException wraps nothing.
                                Throwable cause = e.getCause();
                                lastException = (cause instanceof Exception ex) ? ex : new RuntimeException(cause != null ? cause.getMessage() : e.getMessage(), e);
                                String errorMsg = lastException.getMessage() != null ? lastException.getMessage() : "";
                                
                                // Check for rate limits or server errors
                                if (errorMsg.contains("429") || errorMsg.contains("503") || errorMsg.contains("Too Many Requests") || errorMsg.contains("Service Unavailable")) {
                                    org.slf4j.LoggerFactory.getLogger(OpenRouterLlmConfig.class)
                                            .warn("OpenRouter retry: Model {} (attempt {}/{}) hit rate limit/unavailable. Sleeping for {}ms...", i, attempt, maxRetries, backoffMs);
                                    if (attempt < maxRetries) {
                                        try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                        backoffMs *= 2;
                                    }
                                } else {
                                    // Not a retryable error, break to try the next fallback model
                                    org.slf4j.LoggerFactory.getLogger(OpenRouterLlmConfig.class)
                                            .warn("OpenRouter fallback: Model {} failed with {}, trying next...", i, lastException.getMessage());
                                    break;
                                }
                            } catch (Exception e) {
                                lastException = e;
                                org.slf4j.LoggerFactory.getLogger(OpenRouterLlmConfig.class)
                                        .warn("OpenRouter fallback: Model {} threw unexpected exception, trying next: {}", i, e.getMessage());
                                break;
                            }
                        }
                    }
                    // B9: guaranteed non-null since we always set lastException when catching.
                    throw lastException != null ? lastException : new RuntimeException("All OpenRouter models exhausted with no specific error");
                }
        );
    }

    @Bean("resumeChatModel")
    public ChatModel resumeChatModel() {
        return createChatModelWithFallbacks(llmProperties.getOpenRouterModelName(), 0.1, 8000);
    }

    @Bean("writingChatModel")
    @org.springframework.context.annotation.Primary
    public ChatModel openRouterChatModel() {
        return createChatModelWithFallbacks(llmProperties.getOpenRouterModelName(), llmProperties.getTemperature(), llmProperties.getMaxTokens());
    }
}
