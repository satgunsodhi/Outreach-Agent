package com.outreach.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    @Bean
    public ChatModel openRouterChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(llmProperties.getOpenRouterApiKey())
                .modelName(llmProperties.getOpenRouterModelName())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .build();
    }
}
