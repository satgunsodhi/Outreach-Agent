package com.outreach.agent.service;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;

/**
 * Two-layer fuzzy deduplication for outreach targets.
 *
 * <p><b>Layer 1 — Normalized name matching</b>: Normalizes the company name by stripping
 * whitespace, punctuation, and a small set of legal-entity suffixes (e.g. "Inc", "Ltd").
 * Domain-specific words like "AI" and "Labs" are intentionally preserved so that
 * "OpenAI" and "AI Studio" are NOT collapsed into the same key.</p>
 *
 * <p><b>Layer 2 — Domain matching</b>: Extracts the registered domain from the candidate's
 * jobUrl and checks if any existing target already has that domain in its jobUrl. This
 * catches cases where the same company is discovered with a different recruiter email or
 * a slightly different company name spelling.</p>
 */
@Service
public class CompanyDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(CompanyDeduplicationService.class);

    /**
     * Legal-entity suffixes to strip during normalization.
     * Deliberately excludes domain-specific words (ai, labs, technologies, etc.)
     * to avoid false-positive matches between e.g. "OpenAI" and "AI Studio".
     */
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "inc", "incorporated", "ltd", "limited", "llc", "llp",
            "corp", "corporation", "co", "plc", "gmbh", "ag", "bv", "sas", "sarl"
    );

    private final OutreachTargetRepository repository;

    public CompanyDeduplicationService(OutreachTargetRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns {@code true} if the given candidate target is already represented in the
     * database — either by a fuzzy name match or by a matching job-URL domain.
     *
     * <p>This method performs a single DB call to load all existing company names into
     * memory, so it is efficient for batch scenarios where {@code isDuplicate} is called
     * many times in a loop.
     *
     * <p>For tight batch loops, prefer {@link #isDuplicate(OutreachTarget, Set)} with
     * a pre-loaded name set to avoid N DB round-trips.
     */
    public boolean isDuplicate(OutreachTarget candidate) {
        Set<String> existingNormalized = repository.findAllCompanyNames().stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
        return isDuplicate(candidate, existingNormalized);
    }

    /**
     * E2: Bulk-friendly overload — accepts a pre-loaded set of normalized company names
     * so callers can load it once before a loop instead of hitting the DB on every call.
     */
    public boolean isDuplicate(OutreachTarget candidate, Set<String> existingNormalized) {
        // Layer 1: Normalized name check
        String candidateNorm = normalize(candidate.getCompanyName());
        if (candidateNorm != null && !candidateNorm.isBlank()) {
            if (existingNormalized.contains(candidateNorm)) {
                log.info("Dedup [name]: '{}' normalizes to '{}' which already exists in DB.",
                        candidate.getCompanyName(), candidateNorm);
                return true;
            }
        }

        // Layer 2: Domain-based check
        String domain = extractDomain(candidate.getJobUrl());
        if (domain != null && repository.existsByJobUrlDomain(domain)) {
            log.info("Dedup [domain]: '{}' has domain '{}' which already exists in DB.",
                    candidate.getCompanyName(), domain);
            return true;
        }

        return false;
    }

    /**
     * E2: Returns the set of all normalized company names currently in the DB.
     * Load this once before a deduplication loop and pass it to
     * {@link #isDuplicate(OutreachTarget, Set)} to avoid N DB queries.
     */
    public Set<String> getAllNormalizedCompanyNames() {
        return repository.findAllCompanyNames().stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    /**
     * Normalizes a company name for fuzzy comparison.
     * <ol>
     *   <li>Lowercases the string.</li>
     *   <li>Strips all non-alphanumeric characters (spaces, dots, dashes, apostrophes, etc.).</li>
     *   <li>Strips a trailing legal-entity suffix token if present.</li>
     * </ol>
     *
     * <p>Examples:
     * <ul>
     *   <li>"Hugging Face" → "huggingface"</li>
     *   <li>"HuggingFace Inc." → "huggingface" (stripped "inc")</li>
     *   <li>"OpenAI" → "openai" (NOT stripped — "ai" is not in LEGAL_SUFFIXES)</li>
     *   <li>"DeepMind Ltd" → "deepmind" (stripped "ltd")</li>
     * </ul>
     * </p>
     */
    public String normalize(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // 1. Lowercase and strip all non-alphanumeric characters
        String normalized = name.toLowerCase().replaceAll("[^a-z0-9]", "");

        // 2. Strip a legal suffix token from the END of the string
        //    We iterate from longest to shortest to prefer longer matches first (e.g. "incorporated" > "inc")
        String best = LEGAL_SUFFIXES.stream()
                .filter(suffix -> normalized.endsWith(suffix) && normalized.length() > suffix.length())
                .max(java.util.Comparator.comparingInt(String::length))
                .map(suffix -> normalized.substring(0, normalized.length() - suffix.length()))
                .orElse(normalized);

        return best.isBlank() ? normalized : best;
    }

    /**
     * Extracts the registered domain (host without "www." prefix) from a URL string.
     * Returns {@code null} if the URL is blank, null, or unparseable.
     *
     * <p>Examples:
     * <ul>
     *   <li>"https://www.huggingface.co/jobs/123" → "huggingface.co"</li>
     *   <li>"https://careers.deepmind.com/apply" → "careers.deepmind.com"</li>
     *   <li>null / blank → null</li>
     * </ul>
     * </p>
     */
    public String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) return null;
            // Strip "www." prefix for canonical matching
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }
}
