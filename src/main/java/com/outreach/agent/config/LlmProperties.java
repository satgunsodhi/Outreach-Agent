package com.outreach.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "outreach.llm")
public class LlmProperties {
    private String openRouterApiKey;
    private String openRouterModelName = "openai/gpt-oss-120b:free";
    private Double temperature = 0.2;
    private Integer maxTokens = 4000;
    /** Ordered list of fallback model IDs tried after the primary model returns a 503. */
    private List<String> fallbackModels = List.of(
            "nvidia/llama-3.1-nemotron-ultra-253b:free",
            "google/gemma-3-27b-it:free",
            "deepseek/deepseek-r1-distill-qwen-32b:free"
    );

    public String getOpenRouterApiKey() { return openRouterApiKey; }
    public void setOpenRouterApiKey(String openRouterApiKey) { this.openRouterApiKey = openRouterApiKey; }
    public String getOpenRouterModelName() { return openRouterModelName; }
    public void setOpenRouterModelName(String openRouterModelName) { this.openRouterModelName = openRouterModelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public List<String> getFallbackModels() { return fallbackModels; }
    public void setFallbackModels(List<String> fallbackModels) { this.fallbackModels = fallbackModels; }
}
