package com.outreach.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PdfCacheService {

    private static final Logger log = LoggerFactory.getLogger(PdfCacheService.class);
    private static final String CACHE_FILE_PATH = "data/generated-pdfs/pdf-cache.json";

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    /** Marks cache as modified since the last disk flush. Written by {@link #cachePdfPath}, read by {@link #flushCacheToDisk}. */
    private volatile boolean dirty = false;

    public PdfCacheService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        File file = new File(CACHE_FILE_PATH);
        if (file.exists()) {
            try {
                Map<String, String> loaded = objectMapper.readValue(file, new TypeReference<Map<String, String>>() {});
                cache.putAll(loaded);
                log.info("Loaded {} entries from PDF cache", loaded.size());
            } catch (Exception e) {
                log.error("Failed to load PDF cache from disk", e);
            }
        }
    }

    public String getCachedPdfPath(String jobDescription, String companyResearch) {
        String hash = computeHash(jobDescription, companyResearch);
        String path = cache.get(hash);
        if (path != null) {
            File f = new File(path);
            if (f.exists()) {
                return path;
            } else {
                log.warn("Found cache entry for hash {} but file is missing: {}", hash, path);
                cache.remove(hash);
                saveCacheToDisk();
            }
        }
        return null;
    }

    public void cachePdfPath(String jobDescription, String companyResearch, String pdfPath) {
        String hash = computeHash(jobDescription, companyResearch);
        cache.put(hash, pdfPath);
        dirty = true; // schedule for disk flush; don't write synchronously on every put
        log.info("Cached PDF for hash {}", hash);
    }

    /**
     * B3: Removes any cache entry whose stored file path equals {@code pdfPath}.
     * Must be called whenever a local PDF is deleted so stale cache entries are evicted immediately.
     */
    public void invalidatePath(String pdfPath) {
        if (pdfPath == null || pdfPath.isBlank()) return;
        cache.entrySet().removeIf(entry -> pdfPath.equals(entry.getValue()));
        dirty = true;
        log.debug("Invalidated PDF cache entry for path: {}", pdfPath);
    }

    /** Flushes the cache to disk at most every 30 seconds, only when the cache has been modified. */
    @Scheduled(fixedDelay = 30_000)
    public void flushCacheToDisk() {
        if (dirty) {
            saveCacheToDisk();
            dirty = false;
        }
    }

    private void saveCacheToDisk() {
        try {
            File file = new File(CACHE_FILE_PATH);
            file.getParentFile().mkdirs();
            objectMapper.writeValue(file, cache);
        } catch (Exception e) {
            log.error("Failed to save PDF cache to disk", e);
        }
    }

    // Fix #F: MessageDigest is not thread-safe, so we use a ThreadLocal to avoid re-instantiating it on every call.
    private static final ThreadLocal<java.security.MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    private String computeHash(String jobDescription, String companyResearch) {
        String jd = jobDescription != null ? jobDescription.toLowerCase().trim() : "";
        String cr = companyResearch != null ? companyResearch.toLowerCase().trim() : "";
        String combined = jd + "|" + cr;

        java.security.MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] encodedHash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(encodedHash);
    }
}
