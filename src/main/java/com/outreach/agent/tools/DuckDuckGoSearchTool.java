package com.outreach.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DuckDuckGoSearchTool {

    @Tool("Search the web for a given query and return a list of top search results with titles, snippets, and URLs. Useful for finding job boards, company career pages, and ML roles.")
    public String searchWeb(String query) {
        if (query == null || query.isBlank()) {
            return "{\"error\": \"Query cannot be empty.\"}";
        }
        
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(10000)
                    .get();
            
            Elements results = doc.select(".result");
            StringBuilder sb = new StringBuilder();
            
            for (int i = 0; i < Math.min(results.size(), 10); i++) {
                Element result = results.get(i);
                Element titleElement = result.selectFirst(".result__title > a");
                Element snippetElement = result.selectFirst(".result__snippet");
                
                if (titleElement != null) {
                    String title = titleElement.text();
                    String link = titleElement.attr("href");
                    // DuckDuckGo redirects links, decode the clear url if possible
                    if (link.contains("uddg=")) {
                        String[] parts = link.split("uddg=");
                        if (parts.length > 1) {
                            link = java.net.URLDecoder.decode(parts[1].split("&")[0], "UTF-8");
                        }
                    }
                    String snippet = snippetElement != null ? snippetElement.text() : "";
                    
                    sb.append("Title: ").append(title).append("\n");
                    sb.append("URL: ").append(link).append("\n");
                    sb.append("Snippet: ").append(snippet).append("\n\n");
                }
            }
            
            return sb.toString().isEmpty() ? "No results found." : sb.toString();
        } catch (IOException e) {
            return "{\"error\": \"Search failed: " + e.getMessage() + "\"}";
        }
    }
}
