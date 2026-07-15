package com.outreach.agent.config;

import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;
import com.outreach.agent.service.OpenRouterModelService;

/**
 * LangChain4j ChatModel configuration for the {@code openrouter} Spring profile.
 *
 * <p>Model fallback and response caching are handled transparently by
 * {@link OpenRouterInterceptor}, which is registered as a {@link RestClientCustomizer}
 * and applied to every outbound LLM request. There is no need for application-level
 * retry proxies here.</p>
 */
@Configuration
@Profile("openrouter")
public class OpenRouterLlmConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenRouterLlmConfig.class);

    private final LlmProperties llmProperties;
    private final OpenRouterModelService openRouterModelService;

    public OpenRouterLlmConfig(LlmProperties llmProperties, OpenRouterModelService openRouterModelService) {
        this.llmProperties = llmProperties;
        this.openRouterModelService = openRouterModelService;
    }

    @Value("${logging.level.com.outreach.agent:INFO}")
    private String logLevel;

    /**
     * Registers the {@link OpenRouterInterceptor} as a Spring {@link RestClientCustomizer}.
     * LangChain4j's OpenAI integration uses Spring's RestClient internally, so this
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
                .requestInterceptor(new OpenRouterInterceptor(openRouterModelService::getFallbackModels));
    }

    private ChatModel buildChatModel(double temperature, int maxTokens) {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new OpenRouterInterceptor(openRouterModelService::getFallbackModels));

        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(openRouterModelService.getPrimaryModel())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .logRequests(isTrace)
                .logResponses(isTrace)
                .httpClientBuilder(SpringRestClient.builder().restClientBuilder(builder))
                .build();
    }

    /** Strictly deterministic model for resume generation (temperature=0.1, larger token budget). */
    @Bean("resumeChatModel")
    public ChatModel resumeChatModel() {
        return buildChatModel(0.1, 8000);
    }

    /** General-purpose model for cover letters, research, and discovery. */
    @Bean("writingChatModel")
    @org.springframework.context.annotation.Primary
    public ChatModel openRouterChatModel() {
        return buildChatModel(llmProperties.getTemperature(), llmProperties.getMaxTokens());
    }
}
