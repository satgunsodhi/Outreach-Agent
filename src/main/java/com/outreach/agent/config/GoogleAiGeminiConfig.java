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

    @Value("${logging.level.com.outreach.agent:INFO}")
    private String logLevel;

    @Bean("resumeChatModel")
    public ChatModel resumeChatModel() {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1) // strictly deterministic for resumes
                .logRequestsAndResponses(isTrace)
                .build();
    }

    @Bean("writingChatModel")
    @org.springframework.context.annotation.Primary
    public ChatModel googleAiGeminiChatModel() {
        boolean isTrace = "TRACE".equalsIgnoreCase(logLevel);
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .logRequestsAndResponses(isTrace)
                .build();
    }
}
