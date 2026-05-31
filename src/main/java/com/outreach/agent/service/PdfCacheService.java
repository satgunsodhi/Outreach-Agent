package com.outreach.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PdfCacheService {

    private static final Logger log = LoggerFactory.getLogger(PdfCacheService.class);
    private static final String CACHE_FILE_PATH = "data/generated-pdfs/pdf-cache.json";

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

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
        saveCacheToDisk();
        log.info("Cached PDF for hash {}", hash);
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

    private String computeHash(String jobDescription, String companyResearch) {
        String jd = jobDescription != null ? jobDescription.toLowerCase().trim() : "";
        String cr = companyResearch != null ? companyResearch.toLowerCase().trim() : "";
        String combined = jd + "|" + cr;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 hash", e);
        }
    }
}
