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

        // Return a proxy that tries each model in order
        return (ChatModel) java.lang.reflect.Proxy.newProxyInstance(
                ChatModel.class.getClassLoader(),
                new Class<?>[]{ChatModel.class},
                (proxy, method, args) -> {
                    Exception lastException = null;
                    for (int i = 0; i < models.size(); i++) {
                        ChatModel model = models.get(i);
                        try {
                            return method.invoke(model, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            lastException = (Exception) e.getCause();
                            org.slf4j.LoggerFactory.getLogger(OpenRouterLlmConfig.class)
                                    .warn("OpenRouter fallback: Model {} failed with {}, trying next...", i, lastException.getMessage());
                        } catch (Exception e) {
                            lastException = e;
                        }
                    }
                    throw lastException != null ? lastException : new RuntimeException("No models available");
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
