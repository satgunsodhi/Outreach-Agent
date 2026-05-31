package com.outreach.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class WebScraperTool {

    @Tool("Fetch and extract readable text from a given URL (e.g., a company's About page or a Job Description page).")
    public String scrapeWebPage(String url) {
        if (url == null || url.isBlank()) {
            return "{\"error\": \"URL cannot be null or empty.\"}";
        }
        if (!url.toLowerCase().startsWith("http")) {
            return "{\"error\": \"Invalid URL format. Provide a full HTTP/HTTPS URL, not just a company name.\"}";
        }
        
        int maxAttempts = 3;
        int delayMs = 2000;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Fetch and parse the HTML document
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .timeout(10000)
                        .followRedirects(true)
                        .get();
                
                // Remove noisy elements to improve LLM token efficiency
                doc.select("script, style, nav, footer, header, aside, noscript, iframe, .cookie-banner, #cookie-banner").remove();
                
                // Extract text from the cleaned body
                String text = doc.body().text();
                
                // Truncate to a reasonable length if too large (e.g., 10000 characters)
                if (text.length() > 10000) {
                    text = text.substring(0, 10000) + "... (truncated)";
                }
                
                return text;
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    return "{\"error\": \"Failed to scrape URL after " + maxAttempts + " attempts: " + e.getMessage() + "\"}";
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "{\"error\": \"Scraping interrupted\"}";
                }
                delayMs *= 2;
            }
        }
        return "{\"error\": \"Failed to scrape URL\"}";
    }
}
