package com.outreach.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.agent.CompanyResearchAgent;
import com.outreach.agent.agent.CoverLetterAgent;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.repository.OutreachTargetRepository;

@Service
public class BatchOutreachService {

    private static final Logger log = LoggerFactory.getLogger(BatchOutreachService.class);

    /** Maximum number of automatic retries before a target is permanently marked FAILED. */
    private static final int MAX_RETRIES = 2;

    private final OutreachTargetRepository targetRepository;
    private final CompanyResearchAgent researchAgent;
    private final ResumeOrchestrationService resumeOrchestrationService;
    private final CoverLetterAgent coverLetterAgent;
    private final EmailAutomationService emailAutomationService;
    private final MasterResumeService masterResumeService;
    private final ObjectMapper objectMapper;
    private final GoogleDriveService googleDriveService;

    // Cache to prevent redundant scraping and LLM calls for the same company/URL
    private record CacheEntry(String research, LocalDateTime timestamp) {}
    private final Map<String, CacheEntry> researchCache = new ConcurrentHashMap<>();

    /** Guard to prevent overlapping scheduled runs (LLM calls take 30-60s per target). */
    private final AtomicBoolean processingLock = new AtomicBoolean(false);

    @Value("${app.ci-batch-mode:false}")
    private boolean ciBatchMode;

    public BatchOutreachService(OutreachTargetRepository targetRepository,
                                CompanyResearchAgent researchAgent,
                                ResumeOrchestrationService resumeOrchestrationService,
                                CoverLetterAgent coverLetterAgent,
                                EmailAutomationService emailAutomationService,
                                MasterResumeService masterResumeService,
                                ObjectMapper objectMapper,
                                GoogleDriveService googleDriveService) {
        this.targetRepository = targetRepository;
        this.researchAgent = researchAgent;
        this.resumeOrchestrationService = resumeOrchestrationService;
        this.coverLetterAgent = coverLetterAgent;
        this.emailAutomationService = emailAutomationService;
        this.masterResumeService = masterResumeService;
        this.objectMapper = objectMapper;
        this.googleDriveService = googleDriveService;
    }

    @PostConstruct
    public void recoverStalledTargets() {
        log.info("Checking for any targets that were stuck in PROCESSING state from a previous run...");
        List<OutreachTarget> stuckTargets = targetRepository.findByStatusOrderByIdAsc(TargetStatus.PROCESSING);
        if (!stuckTargets.isEmpty()) {
            log.info("Found {} stuck targets. Reverting them to PENDING.", stuckTargets.size());
            for (OutreachTarget target : stuckTargets) {
                target.setStatus(TargetStatus.PENDING);
                target.setErrorReason("Server restarted or crashed during processing. Requeued.");
                targetRepository.save(target);
            }
        }
    }

    // Run every 10 seconds to draft targets (disabled in CI batch mode — CiBatchRunner calls directly)
    @Scheduled(fixedDelay = 10000)
    public void scheduledProcessPendingTargets() {
        if (ciBatchMode) {
            return; // CiBatchRunner drives processing in CI mode — avoid race condition
        }
        processPendingTargets();
    }

    public void processPendingTargets() {
        if (!processingLock.compareAndSet(false, true)) {
            log.debug("Skipping processPendingTargets — a previous run is still in progress.");
            return;
        }

        try {
            processPendingTargetsInternal();
        } finally {
            processingLock.set(false);
        }
    }

    private void processPendingTargetsInternal() {
        List<OutreachTarget> pendingTargets = targetRepository.findByStatusOrderByIdAsc(TargetStatus.PENDING);

        if (!pendingTargets.isEmpty()) {
            log.info("Found {} pending targets to process.", pendingTargets.size());
            
            // Clean up any orphaned PDFs from previous runs
            try {
                Path pdfDir = Path.of("data/generated-pdfs");
                if (Files.exists(pdfDir)) {
                    try (java.util.stream.Stream<Path> paths = Files.list(pdfDir)) {
                        paths.filter(p -> p.toString().endsWith(".pdf"))
                             .forEach(p -> {
                                 try {
                                     Files.deleteIfExists(p);
                                 } catch (Exception e) {
                                     log.debug("Failed to delete old PDF: {}", p);
                                 }
                             });
                    }
                }
            } catch (Exception e) {
                log.warn("Error cleaning up old PDFs: {}", e.getMessage());
            }
        }

        for (OutreachTarget target : pendingTargets) {
            try {
                log.debug("Starting processing for target: {}", target.getCompanyName());
                log.info("Starting processing for target ID: {}", target.getId());
                target.setStatus(TargetStatus.PROCESSING);
                target.setProcessingStartedAt(LocalDateTime.now());
                targetRepository.save(target);

                // 1. Research Company (with Caching)
                String companyResearch = "No specific research provided.";
                String cacheKey = target.getJobUrl() != null && !target.getJobUrl().isBlank() 
                        ? target.getJobUrl() 
                        : target.getCompanyName();

                if (cacheKey != null && !cacheKey.isBlank()) {
                    CacheEntry entry = researchCache.get(cacheKey);
                    if (entry != null && entry.timestamp().isAfter(LocalDateTime.now().minusHours(24))) {
                        companyResearch = entry.research();
                        log.debug("Using cached research for {}", cacheKey);
                    } else {
                        log.debug("Fetching new research for {}", cacheKey);
                        companyResearch = researchAgent.researchCompany(cacheKey);
                        researchCache.put(cacheKey, new CacheEntry(companyResearch, LocalDateTime.now()));
                    }
                }

                // 2. Generate Tailored Resume (PDF)
                String jd = target.getJobDescription() != null && !target.getJobDescription().isBlank()
                        ? target.getJobDescription()
                        : "General position at " + target.getCompanyName();
                String pdfPathStr = resumeOrchestrationService.generateTailoredResume(jd, companyResearch);
                pdfPathStr = sanitizePdfPath(pdfPathStr);

                // 3. Generate Cover Letter & Subject
                String masterResumeJson = objectMapper.writeValueAsString(masterResumeService.getMasterResume());
                String roleName = target.getJobDescription() != null ? target.getJobDescription() : "ML Intern";
                String coverLetterBody = coverLetterAgent.generateCoverLetter(
                        masterResumeJson, roleName, target.getCompanyName(), jd, companyResearch, target.getJobUrl());
                
                // Get personal info for dynamic links and names
                var personalInfo = masterResumeService.getMasterResume().personalInfo();
                String candidateName = personalInfo.name();
                String candidateFirstName = candidateName.contains(" ") ? candidateName.substring(0, candidateName.indexOf(' ')) : candidateName;

                // Clean up any remaining placeholders in the LLM output
                coverLetterBody = fillPlaceholders(coverLetterBody, candidateName, target.getCompanyName());

                // Strip any existing sign-off generated by the LLM
                String cleanBody = coverLetterBody.replaceAll("(?i)(Best|Sincerely|Regards|Thanks|Cheers|Yours|Warmly)[\\s\\S]*$", "").trim();
                String github = personalInfo.github();
                if (github != null && !github.startsWith("http")) github = "https://" + github;
                String linkedin = personalInfo.linkedin();
                if (linkedin != null && !linkedin.startsWith("http")) linkedin = "https://" + linkedin;

                // Upload resume to Google Drive and append link to cover letter
                String driveLink = googleDriveService.uploadResume(pdfPathStr);
                
                String signature = "\n\nBest,\n"
                        + "<strong>" + candidateName + "</strong>\n"
                        + "<a href=\"https://satgunsodhi.vercel.app\">Portfolio</a> | "
                        + "<a href=\"" + github + "\">GitHub</a> | "
                        + "<a href=\"" + linkedin + "\">LinkedIn</a>\n"
                        + "<a href=\"" + driveLink + "\">View My Tailored Resume</a>";
                
                coverLetterBody = cleanBody + signature;
                
                String subject = target.getSubject() != null && !target.getSubject().isBlank()
                        ? target.getSubject()
                        : coverLetterAgent.generateSubject(target.getCompanyName(), roleName);

                // Ensure subject doesn't contain newlines or quotes from LLM formatting
                subject = subject.replaceAll("[\r\n\"]+", "").trim();

                // Clean placeholders in subject line too
                subject = fillPlaceholders(subject, candidateName, target.getCompanyName());

                if (containsPlaceholderTokens(coverLetterBody) || containsPlaceholderTokens(subject)) {
                    throw new IllegalStateException("Generated outreach text still contains placeholder tokens");
                }

                // 4. Create Gmail Draft immediately
                String draftId = emailAutomationService.sendResumeEmail(
                        target.getRecipientEmail(),
                        subject,
                        null, // Resume is linked via Google Drive in the cover letter body
                        coverLetterBody
                );

                if (draftId == null) {
                    throw new IllegalStateException("Gmail draft creation returned null — Gmail API may be unavailable or token expired");
                }
                
                // Clean up the local PDF after the draft is created (resume is on Drive)
                try {
                    if (pdfPathStr != null) {
                        Files.deleteIfExists(Path.of(pdfPathStr));
                    }
                } catch (Exception ex) {
                    log.warn("Could not delete local PDF: {}", pdfPathStr);
                }

                // 5. Update State to DRAFT_CREATED
                target.setGeneratedPdfPath(null);
                target.setDraftedCoverLetter(coverLetterBody);
                target.setSubject(subject);
                // 5. Update Target Status
                target.setStatus(TargetStatus.DRAFT_CREATED);
                target.setEmailSentAt(LocalDateTime.now()); // reusing field to mean "draft created at"
                target.setFollowUpScheduledAt(calculateNextWorkingDay8AmIst());
                target.setProcessingCompletedAt(LocalDateTime.now());
                
                targetRepository.save(target);
                log.debug("Successfully drafted target: {}. Draft ID: {}", target.getCompanyName(), draftId);
                log.info("Successfully drafted target ID: {}. Draft ID: {}", target.getId(), draftId);

            } catch (Exception e) {
                handleTargetFailure(target, e);
            }
        }
    }

    // Run every hour to check for follow-ups (disabled in CI batch mode)
    @Scheduled(fixedRate = 3600000)
    public void processFollowUps() {
        if (ciBatchMode) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<OutreachTarget> dueFollowUps = targetRepository.findByStatusAndFollowUpScheduledAtBefore(TargetStatus.DRAFT_CREATED, now);

        for (OutreachTarget target : dueFollowUps) {
            try {
                int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(target.getEmailSentAt(), now);
                if (daysSince < 1) daysSince = 1;

                String candidateName = masterResumeService.getMasterResume().personalInfo().name();
                String followUpDraftId = emailAutomationService.sendFollowUp(
                        target.getRecipientEmail(), target.getSubject(), daysSince,
                        target.getCompanyName(), target.getJobDescription(), candidateName);

                target.setStatus(TargetStatus.FOLLOW_UP_DRAFT_CREATED);
                target.setGmailDraftId(followUpDraftId); // overwrite with follow-up draft ID
                targetRepository.save(target);
                log.debug("Follow-up draft created for {}", target.getCompanyName());
                log.info("Follow-up draft created for target ID: {}", target.getId());
            } catch (Exception e) {
                target.setStatus(TargetStatus.FAILED);
                target.setErrorReason("Follow-up draft failed: " + e.getMessage());
                targetRepository.save(target);
                log.debug("Follow-up draft failed for {}: {}", target.getCompanyName(), e.getMessage());
                log.error("Follow-up draft failed for target ID: {}: {}", target.getId(), e.getMessage());
            }
        }
    }

    /**
     * Handles a target processing failure with retry logic.
     * If the target has been retried fewer than MAX_RETRIES times, it is reset to PENDING
     * for another attempt. Otherwise, it is permanently marked FAILED.
     */
    private void handleTargetFailure(OutreachTarget target, Exception e) {
        int retries = target.getRetryCount();
        if (retries < MAX_RETRIES) {
            target.setRetryCount(retries + 1);
            target.setStatus(TargetStatus.PENDING);
            target.setErrorReason("Retry " + (retries + 1) + "/" + MAX_RETRIES + ": " + e.getMessage());
            targetRepository.save(target);
            log.debug("Transient failure for {} (retry {}/{}): {}",
                    target.getCompanyName(), retries + 1, MAX_RETRIES, e.getMessage());
            log.warn("Transient failure for target ID: {} (retry {}/{}): {}",
                    target.getId(), retries + 1, MAX_RETRIES, e.getMessage());
        } else {
            target.setStatus(TargetStatus.FAILED);
            target.setErrorReason(e.getMessage());
            targetRepository.save(target);
            log.debug("Permanently failed target {} after {} retries: {}",
                    target.getCompanyName(), MAX_RETRIES, e.getMessage(), e);
            log.error("Permanently failed target ID: {} after {} retries: {}",
                    target.getId(), MAX_RETRIES, e.getMessage(), e);
        }
    }

    private LocalDateTime calculateNextWorkingDay8AmIst() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime nowIst = ZonedDateTime.now(istZone);
        
        ZonedDateTime nextDay = nowIst.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        
        while (nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextDay = nextDay.plusDays(1);
        }
        
        return nextDay.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    // ── Regex patterns for placeholder detection ──
    // Matches anything inside [...], <...>, or {...} that looks like a placeholder
    // e.g. [Hiring Manager's Name], <Recruiter Name>, {Company Name}, [Name], etc.
    private static final Pattern BRACKET_PLACEHOLDER = Pattern.compile(
            "\\[(?:Hiring Manager(?:'s)?(?:\\s+Name)?|Recruiter(?:'s)?(?:\\s+Name)?|" +
            "Your\\s+Name|Name|First\\s+Name|Last\\s+Name|Recipient(?:'s)?(?:\\s+Name)?|" +
            "Contact\\s+Name|HR\\s+Manager|Team\\s+Lead|Founder(?:'s)?(?:\\s+Name)?|" +
            "Company(?:\\s+Name)?|Company|Role|Position|Job\\s+Title)\\]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANGLE_PLACEHOLDER = Pattern.compile(
            "<(?:Hiring Manager(?:'s)?(?:\\s+Name)?|Recruiter(?:'s)?(?:\\s+Name)?|" +
            "PRIVATE_PERSON|Your\\s+Name|Name|First\\s+Name|Recipient(?:'s)?(?:\\s+Name)?|" +
            "COMPANY_NAME|Company(?:\\s+Name)?|HR\\s+Manager|Founder(?:'s)?(?:\\s+Name)?)>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CURLY_PLACEHOLDER = Pattern.compile(
            "\\{(?:name|companyName|company|hiringManager|recruiter|recipientName|" +
            "yourName|firstName|lastName|position|role|jobTitle)\\}",
            Pattern.CASE_INSENSITIVE);

    // Matches common greeting lines with placeholders so we can clean them up
    // e.g. "Hi [Hiring Manager's Name]," → "Hi," or "Dear [Recruiter]," → "Hi,"
    private static final Pattern GREETING_WITH_PLACEHOLDER = Pattern.compile(
            "^(Hi|Hello|Dear|Hey)\\s+" +
            "(?:\\[.*?\\]|<.*?>|\\{.*?\\})" +
            "(\\s*[,:]?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private String fillPlaceholders(String text, String candidateName, String companyName) {
        if (text == null) {
            return null;
        }

        // Step 1: Direct literal replacements (fast path for known exact tokens)
        text = text
                .replace("[Your Name]", candidateName)
                .replace("<PRIVATE_PERSON>", candidateName)
                .replace("YOUR_NAME", candidateName)
                .replace("{name}", candidateName)
                .replace("[Company]", companyName)
                .replace("[Company Name]", companyName)
                .replace("{companyName}", companyName)
                .replace("<COMPANY_NAME>", companyName);

        // Step 2: Fix greeting lines with any remaining placeholders
        // "Hi [Hiring Manager's Name]," → "Hi,"
        // "Dear [Recruiter]," → "Hi,"
        text = GREETING_WITH_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String greeting = mr.group(1);
            String punctuation = mr.group(2);
            // Normalize "Dear" to "Hi" for cold outreach
            if (greeting.equalsIgnoreCase("Dear")) {
                greeting = "Hi";
            }
            return greeting + (punctuation.isEmpty() ? "," : punctuation);
        });

        // Step 3: Regex-replace any remaining name/person placeholders with candidate name
        text = replacePersonPlaceholders(text, candidateName);

        // Step 4: Regex-replace any remaining company placeholders with company name
        text = replaceCompanyPlaceholders(text, companyName);

        // Step 5: Clean up any stray em-dashes the LLM loves to insert
        text = text.replace("\u2014", "-").replace("\u2013", "-").replace("\u2011", "-");

        // Step 6: Collapse any double newlines introduced by removals
        text = text.replaceAll("\n{3,}", "\n\n");

        return text.trim();
    }

    private String replacePersonPlaceholders(String text, String candidateName) {
        // Replace name-related bracket placeholders with the candidate's name
        // (appropriate for sign-offs like "Best, [Your Name]")
        text = BRACKET_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company") || content.contains("role") || content.contains("position") || content.contains("job")) {
                return mr.group(); // Skip, handled by company replacements
            }
            return candidateName;
        });
        text = ANGLE_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company")) {
                return mr.group(); // Skip
            }
            return candidateName;
        });
        text = CURLY_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company") || content.contains("role") || content.contains("position") || content.contains("jobtitle")) {
                return mr.group(); // Skip
            }
            return candidateName;
        });
        return text;
    }

    private String replaceCompanyPlaceholders(String text, String companyName) {
        // Now replace company/role placeholders
        text = Pattern.compile(
                "\\[(?:Company(?:\\s+Name)?|Role|Position|Job\\s+Title)\\]",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        text = Pattern.compile(
                "<(?:COMPANY_NAME|Company(?:\\s+Name)?)>",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        text = Pattern.compile(
                "\\{(?:companyName|company|position|role|jobTitle)\\}",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        return text;
    }

    /**
     * Final safety check: returns true if text STILL contains obvious placeholder tokens
     * that should never appear in a sent email.
     */
    private boolean containsPlaceholderTokens(String text) {
        if (text == null) {
            return false;
        }
        // Check for any remaining bracket/angle/curly placeholders using a broad regex
        // This catches anything like [Something], <SOMETHING>, {something}
        // that contains keywords suggestive of a placeholder
        String upper = text.toUpperCase();
        return upper.contains("YOUR_NAME")
                || upper.contains("YOUR COMPANY")
                || upper.contains("[YOUR NAME]")
                || upper.contains("[COMPANY NAME]")
                || upper.contains("<PRIVATE_PERSON>")
                || upper.contains("<COMPANY_NAME>")
                || upper.contains("{NAME}")
                || upper.contains("{COMPANYNAME}")
                || upper.contains("[HIRING MANAGER")
                || upper.contains("[RECRUITER")
                || upper.contains("<HIRING MANAGER")
                || upper.contains("{HIRINGMANAGER");
    }

    private String sanitizePdfPath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim();
        // Look for data/generated-pdfs/resume-....pdf within the output in case of markdown or extra conversational text
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:[a-zA-Z]:)?[/\\\\\\w\\.\\-]+data/generated-pdfs/resume-[\\w\\.\\-]+pdf").matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(0).replace("\\", "/");
        }
        
        // Fallback: strip quotes, backticks
        if (trimmed.startsWith("`") && trimmed.endsWith("`")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.trim().replace("\\", "/");
    }
}
