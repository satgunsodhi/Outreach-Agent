package com.outreach.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "outreach.llm")
public class LlmProperties {
    private String openRouterApiKey;
    private String openRouterModelName = "google/gemini-1.5-flash";
    private Double temperature = 0.2;

    public String getOpenRouterApiKey() { return openRouterApiKey; }
    public void setOpenRouterApiKey(String openRouterApiKey) { this.openRouterApiKey = openRouterApiKey; }
    public String getOpenRouterModelName() { return openRouterModelName; }
    public void setOpenRouterModelName(String openRouterModelName) { this.openRouterModelName = openRouterModelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
}
