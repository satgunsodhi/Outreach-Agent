package com.outreach.agent.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.agent.CompanyResearchAgent;

import jakarta.annotation.PostConstruct;

@Service
public class ResearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResearchCacheService.class);
    private static final String CACHE_FILE_PATH = "data/research-cache.json";

    private final CompanyResearchAgent researchAgent;
    private final ObjectMapper objectMapper;

    /**
     * B2: Stores CompletableFuture values so that concurrent callers for the same company
     * share a single in-flight LLM request instead of triggering duplicate calls.
     * E6: CacheEntry records the result plus a timestamp for 24-hour TTL enforcement.
     */
    private record CacheEntry(String research, LocalDateTime timestamp) {}

    /** In-memory store: cacheKey → CompletableFuture<CacheEntry> */
    private final ConcurrentHashMap<String, CompletableFuture<CacheEntry>> inFlightMap = new ConcurrentHashMap<>();

    /** Marks cache as modified since last disk flush. */
    private volatile boolean dirty = false;

    public ResearchCacheService(CompanyResearchAgent researchAgent, ObjectMapper objectMapper) {
        this.researchAgent = researchAgent;
        this.objectMapper = objectMapper;
    }

    /** E6: Load persisted cache from disk on startup. */
    @PostConstruct
    public void init() {
        File file = new File(CACHE_FILE_PATH);
        if (file.exists()) {
            try {
                Map<String, SerializedEntry> loaded = objectMapper.readValue(file,
                        new TypeReference<Map<String, SerializedEntry>>() {});
                for (Map.Entry<String, SerializedEntry> e : loaded.entrySet()) {
                    CacheEntry entry = new CacheEntry(e.getValue().research(), e.getValue().timestamp());
                    CompletableFuture<CacheEntry> future = CompletableFuture.completedFuture(entry);
                    inFlightMap.put(e.getKey(), future);
                }
                log.info("Loaded {} research cache entries from disk.", loaded.size());
            } catch (Exception ex) {
                log.warn("Could not load research cache from disk: {}", ex.getMessage());
            }
        }
    }

    public String getOrFetchResearch(String companyName, String jobUrl) {
        String cacheKey = (jobUrl != null && !jobUrl.isBlank()) ? jobUrl : companyName;
        if (cacheKey == null || cacheKey.isBlank()) {
            return "No specific research provided.";
        }

        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

        // B2: computeIfAbsent is atomic — only one thread creates the future, others share it.
        CompletableFuture<CacheEntry> future = inFlightMap.compute(cacheKey, (key, existing) -> {
            if (existing != null) {
                // Check if the completed future holds a fresh entry
                if (existing.isDone() && !existing.isCompletedExceptionally()) {
                    try {
                        CacheEntry entry = existing.getNow(null);
                        if (entry != null && entry.timestamp().isAfter(cutoff)) {
                            log.debug("Cache hit for {}", key);
                            return existing; // Re-use fresh entry
                        }
                    } catch (Exception ignored) {}
                } else if (!existing.isDone()) {
                    log.debug("Cache in-flight for {}: waiting on existing future", key);
                    return existing; // Another thread is fetching — share the same future
                }
            }
            // Miss or stale: start a new async fetch
            log.debug("Cache miss for {}. Fetching research via LLM.", key);
            return CompletableFuture.supplyAsync(() -> {
                String research = researchAgent.researchCompany(key);
                CacheEntry newEntry = new CacheEntry(research, LocalDateTime.now());
                dirty = true;
                return newEntry;
            });
        });

        try {
            return future.get().research(); // Block until the (possibly shared) future completes
        } catch (Exception e) {
            log.error("Research fetch failed for {}: {}", cacheKey, e.getMessage());
            return "Research unavailable: " + e.getMessage();
        }
    }

    /** E6: Flush research cache to disk every 5 minutes if modified. */
    @Scheduled(fixedDelay = 300_000)
    public void flushCacheToDisk() {
        if (!dirty) return;
        try {
            File file = new File(CACHE_FILE_PATH);
            file.getParentFile().mkdirs();

            Map<String, SerializedEntry> toWrite = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, CompletableFuture<CacheEntry>> e : inFlightMap.entrySet()) {
                if (e.getValue().isDone() && !e.getValue().isCompletedExceptionally()) {
                    CacheEntry entry = e.getValue().getNow(null);
                    if (entry != null) {
                        toWrite.put(e.getKey(), new SerializedEntry(entry.research(), entry.timestamp()));
                    }
                }
            }
            objectMapper.writeValue(file, toWrite);
            dirty = false;
            log.debug("Flushed {} research cache entries to disk.", toWrite.size());
        } catch (Exception ex) {
            log.error("Failed to flush research cache to disk: {}", ex.getMessage());
        }
    }

    /** DTO for JSON serialization of cache entries. */
    record SerializedEntry(String research, LocalDateTime timestamp) {}
}
