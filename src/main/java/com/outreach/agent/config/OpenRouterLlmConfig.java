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

    private OpenAiChatModel.OpenAiChatModelBuilder baseChatModelBuilder() {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(llmProperties.getOpenRouterModelName())
                .logRequests(isTrace)
                .logResponses(isTrace);
    }

    @Bean("resumeChatModel")
    public ChatModel resumeChatModel() {
        return baseChatModelBuilder()
                .temperature(0.1) // strictly deterministic for resumes
                .maxTokens(8000)  // higher token limit to avoid truncation
                .build();
    }

    @Bean("writingChatModel")
    @org.springframework.context.annotation.Primary
    public ChatModel openRouterChatModel() {
        return baseChatModelBuilder()
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .build();
    }
}
