package com.outreach.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean("resumeChatModel")
    public ChatModel resumeChatModel() {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(llmProperties.getOpenRouterModelName())
                .temperature(0.1) // strictly deterministic for resumes
                .maxTokens(8000)  // higher token limit to avoid truncation
                .logRequests(isTrace)
                .logResponses(isTrace)
                .build();
    }

    @Bean("writingChatModel")
    @org.springframework.context.annotation.Primary
    public ChatModel openRouterChatModel() {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(llmProperties.getOpenRouterModelName())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .logRequests(isTrace)
                .logResponses(isTrace)
                .build();
    }
}
