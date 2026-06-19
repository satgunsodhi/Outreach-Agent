package com.outreach.agent.runner;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.agent.TargetDiscoveryAgent;
import com.outreach.agent.repository.OutreachTargetRepository;
import com.outreach.agent.service.BatchOutreachService;
import com.outreach.agent.service.CompanyDeduplicationService;
import com.outreach.agent.service.GoogleOAuthService;

@Component
public class CiBatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CiBatchRunner.class);

    private final BatchOutreachService batchOutreachService;
    private final OutreachTargetRepository repository;
    private final ApplicationContext context;
    private final GoogleOAuthService googleOAuthService;
    private final TargetDiscoveryAgent targetDiscoveryAgent;
    private final CompanyDeduplicationService deduplicationService;

    @Value("${app.ci-batch-mode:false}")
    private boolean ciBatchMode;

    @Value("${app.targets-file:targets.json}")
    private String targetsFilePath;

    /** B5: Role and region for autonomous target discovery — configurable, not hardcoded. */
    @Value("${app.discovery.role:ML Engineer}")
    private String discoveryRole;

    @Value("${app.discovery.region:Remote or India}")
    private String discoveryRegion;

    public CiBatchRunner(BatchOutreachService batchOutreachService, OutreachTargetRepository repository,
            ApplicationContext context, GoogleOAuthService googleOAuthService,
            TargetDiscoveryAgent targetDiscoveryAgent, CompanyDeduplicationService deduplicationService) {
        this.batchOutreachService = batchOutreachService;
        this.repository = repository;
        this.context = context;
        this.googleOAuthService = googleOAuthService;
        this.targetDiscoveryAgent = targetDiscoveryAgent;
        this.deduplicationService = deduplicationService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!ciBatchMode) {
            return;
        }

        log.info("==================================================");
        log.info("   RUNNING OUTREACH AGENT IN CI BATCH MODE");
        log.info("==================================================");

        if (!googleOAuthService.isAvailable()) {
            log.error("Google OAuth Service is not available. Check GOOGLE_REFRESH_TOKEN and GOOGLE_CLIENT_SECRETS_JSON.");
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        // 1. Load targets
        File targetsFile = new File(targetsFilePath);
        if (targetsFile.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            List<OutreachTarget> targets = mapper.readValue(targetsFile, new TypeReference<List<OutreachTarget>>() {});
            // E2: Load the full set of normalized company names once before iterating.
            java.util.Set<String> existingNames = deduplicationService.getAllNormalizedCompanyNames();
            int added = 0;
            for (OutreachTarget t : targets) {
                if (!deduplicationService.isDuplicate(t, existingNames)) {
                    t.setStatus(TargetStatus.PENDING);
                    repository.save(t);
                    // Add the new entry's normalized name to the in-memory set so sibling targets
                    // in the same file are also checked against it without another DB query.
                    String norm = deduplicationService.normalize(t.getCompanyName());
                    if (norm != null) existingNames.add(norm);
                    added++;
                } else {
                    log.info("Skipping duplicate target from targets.json: '{}'", t.getCompanyName());
                }
            }
            log.info("Loaded {} targets. {} new targets added.", targets.size(), added);
        } else {
            log.info("Targets file not found at: {}", targetsFilePath);
        }

        // 2. Process all PENDING targets
        // processPendingTargets() is synchronous — no poll loop needed; it returns only when all targets are processed.
        log.info("Starting target processing...");
        batchOutreachService.processPendingTargets();
        log.info("Target processing complete.");

        // 4. Idle Target Discovery
        if (repository.findByStatusOrderByIdAsc(TargetStatus.PENDING).isEmpty()) {
            log.info("Queue is idle. Triggering autonomous target discovery...");
            try {
                // B5: use configurable role/region instead of hardcoded strings.
                String rawResult = targetDiscoveryAgent.discoverTargets(discoveryRole, discoveryRegion);
                log.info("Raw discovery result: {}", rawResult);

                // Strip markdown fences the LLM may have wrapped the JSON in
                String cleanJson = rawResult.trim()
                        .replaceAll("(?s)^```json\\s*", "")
                        .replaceAll("(?s)```\\s*$", "");

                ObjectMapper mapper = new ObjectMapper();
                List<OutreachTarget> newTargets;
                try {
                    newTargets = mapper.readValue(cleanJson, new TypeReference<List<OutreachTarget>>() {});
                } catch (Exception parseEx) {
                    log.error("Failed to parse target discovery JSON (LLM may have returned non-JSON): {}", parseEx.getMessage());
                    // E10: Persist the raw result so it can be inspected post-run.
                    try {
                        java.nio.file.Path debugDir = java.nio.file.Path.of("data/debug");
                        java.nio.file.Files.createDirectories(debugDir);
                        String ts = java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                        java.nio.file.Files.writeString(
                                debugDir.resolve("discovery_" + ts + ".txt"), rawResult);
                        log.info("Saved raw discovery output to data/debug/discovery_{}.txt", ts);
                    } catch (Exception dumpEx) {
                        log.warn("Could not write discovery debug file: {}", dumpEx.getMessage());
                    }
                    newTargets = java.util.List.of();
                }

                // E2: pre-load normalized names once before iterating discovered targets.
                java.util.Set<String> existingNames = deduplicationService.getAllNormalizedCompanyNames();
                int added = 0;
                for (OutreachTarget t : newTargets) {
                    if (t.getCompanyName() == null || t.getCompanyName().isBlank()) {
                        log.warn("Skipping discovered target with blank companyName.");
                        continue;
                    }
                    if (!isValidEmail(t.getRecipientEmail())) {
                        log.warn("Skipping discovered target '{}' with invalid recipientEmail: {}",
                                t.getCompanyName(), t.getRecipientEmail());
                        continue;
                    }
                    if (!deduplicationService.isDuplicate(t, existingNames)) {
                        t.setStatus(TargetStatus.PENDING);
                        repository.save(t);
                        String norm = deduplicationService.normalize(t.getCompanyName());
                        if (norm != null) existingNames.add(norm);
                        added++;
                    } else {
                        log.info("Skipping duplicate discovered target: '{}'", t.getCompanyName());
                    }
                }

                if (added > 0) {
                    log.info("Found {} new targets! Processing them now.", added);
                    // processPendingTargets() is synchronous — no poll loop needed.
                    batchOutreachService.processPendingTargets();
                } else {
                    log.info("No new targets discovered.");
                }
            } catch (Exception e) {
                log.error("Failed during autonomous target discovery", e);
            }
        }

        log.info("==================================================");
        log.info("   CI BATCH MODE COMPLETE. EXITING.");
        log.info("==================================================");
        
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Basic email format validation — rejects null, blank, or addresses missing an @-domain-TLD structure. */
    private boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) return false;
        return email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
}
