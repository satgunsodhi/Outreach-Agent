package com.outreach.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("gemini-studio")
public class GoogleAiGeminiConfig {

    @Value("${langchain4j.google.ai.gemini.api-key}")
    private String apiKey;

    @Value("${langchain4j.google.ai.gemini.model-name:gemini-2.5-flash}")
    private String modelName;

    @Value("${langchain4j.google.ai.gemini.temperature:0.2}")
    private Double temperature;

    @Bean
    public ChatModel googleAiGeminiChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }
}
