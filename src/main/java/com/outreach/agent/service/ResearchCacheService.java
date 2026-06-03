package com.outreach.agent.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.outreach.agent.agent.CompanyResearchAgent;

@Service
public class ResearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResearchCacheService.class);

    private final CompanyResearchAgent researchAgent;

    private record CacheEntry(String research, LocalDateTime timestamp) {}
    private final Map<String, CacheEntry> researchCache = new ConcurrentHashMap<>();

    public ResearchCacheService(CompanyResearchAgent researchAgent) {
        this.researchAgent = researchAgent;
    }

    public String getOrFetchResearch(String companyName, String jobUrl) {
        String cacheKey = jobUrl != null && !jobUrl.isBlank() ? jobUrl : companyName;
        if (cacheKey == null || cacheKey.isBlank()) {
            return "No specific research provided.";
        }

        // Fix #4: check cache without holding any lock, only enter a write-path when a miss occurs.
        // Using compute() would hold a per-bucket lock for the entire duration of the blocking LLM call.
        CacheEntry cached = researchCache.get(cacheKey);
        if (cached != null && cached.timestamp().isAfter(LocalDateTime.now().minusHours(24))) {
            log.debug("Using cached research for {}", cacheKey);
            return cached.research();
        }

        log.debug("Fetching new research for {}", cacheKey);
        String research = researchAgent.researchCompany(cacheKey);
        researchCache.put(cacheKey, new CacheEntry(research, LocalDateTime.now()));
        return research;
    }
}
