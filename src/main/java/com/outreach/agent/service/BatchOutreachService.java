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
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.agent.CompanyResearchAgent;
import com.outreach.agent.agent.CoverLetterAgent;
import com.outreach.agent.model.OutreachTarget;
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
    private final Map<String, String> researchCache = new ConcurrentHashMap<>();

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

    // Run every 10 seconds to draft targets (disabled in CI batch mode — CiBatchRunner calls directly)
    @Scheduled(fixedDelay = 10000)
    public void scheduledProcessPendingTargets() {
        if (ciBatchMode) {
            return; // CiBatchRunner drives processing in CI mode — avoid race condition
        }
        processPendingTargets();
    }

    public void processPendingTargets() {
        List<OutreachTarget> pendingTargets = targetRepository.findByStatus("PENDING");

        if (!pendingTargets.isEmpty()) {
            log.info("Found {} pending targets to process.", pendingTargets.size());
        }

        for (OutreachTarget target : pendingTargets) {
            try {
                log.debug("Starting processing for target: {}", target.getCompanyName());
                log.info("Starting processing for target ID: {}", target.getId());
                target.setStatus("PROCESSING");
                targetRepository.save(target);

                // 1. Research Company (with Caching)
                String companyResearch = "No specific research provided.";
                String cacheKey = target.getJobUrl() != null && !target.getJobUrl().isBlank() 
                        ? target.getJobUrl() 
                        : target.getCompanyName();

                if (cacheKey != null && !cacheKey.isBlank()) {
                    if (researchCache.containsKey(cacheKey)) {
                        companyResearch = researchCache.get(cacheKey);
                        log.debug("Using cached research for {}", cacheKey);
                    } else {
                        log.debug("Fetching new research for {}", cacheKey);
                        companyResearch = researchAgent.researchCompany(cacheKey);
                        researchCache.put(cacheKey, companyResearch);
                    }
                }

                // 2. Generate Tailored Resume (PDF)
                String jd = target.getJobDescription() != null && !target.getJobDescription().isBlank()
                        ? target.getJobDescription()
                        : "General position at " + target.getCompanyName();
                String pdfPathStr = resumeOrchestrationService.generateTailoredResume(jd);

                // 3. Generate Cover Letter & Subject
                String masterResumeJson = objectMapper.writeValueAsString(masterResumeService.getMasterResume());
                String roleName = target.getJobDescription() != null ? target.getJobDescription() : "ML Intern";
                String coverLetterBody = coverLetterAgent.generateCoverLetter(
                        masterResumeJson, roleName, target.getCompanyName(), jd, companyResearch);
                
                // Clean up any remaining placeholders in the LLM output
                coverLetterBody = fillPlaceholders(coverLetterBody, "Satgun Singh Sodhi", target.getCompanyName());

                // Upload resume to Google Drive and append link to cover letter
                String driveLink = googleDriveService.uploadResume(pdfPathStr);
                coverLetterBody = coverLetterBody.trim() + "\n\nResume Link: " + driveLink;
                
                String subject = target.getSubject() != null && !target.getSubject().isBlank()
                        ? target.getSubject()
                        : coverLetterAgent.generateSubject(target.getCompanyName(), roleName);

                // Clean placeholders in subject line too
                subject = fillPlaceholders(subject, "Satgun Singh Sodhi", target.getCompanyName());

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
                target.setGmailDraftId(draftId);
                target.setStatus("DRAFT_CREATED");
                target.setEmailSentAt(LocalDateTime.now()); // reusing field to mean "draft created at"
                target.setFollowUpScheduledAt(calculateNextWorkingDay8AmIst());
                
                targetRepository.save(target);
                log.debug("Successfully drafted target: {}. Draft ID: {}", target.getCompanyName(), draftId);
                log.info("Successfully drafted target ID: {}. Draft ID: {}", target.getId(), draftId);

            } catch (Exception e) {
                handleTargetFailure(target, e);
            }
        }
    }

    // Run every hour to check for follow-ups (disabled in CI batch mode)
    @Scheduled(fixedDelay = 3600000)
    public void processFollowUps() {
        if (ciBatchMode) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<OutreachTarget> dueFollowUps = targetRepository.findByStatusAndFollowUpScheduledAtBefore("DRAFT_CREATED", now);

        for (OutreachTarget target : dueFollowUps) {
            try {
                int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(target.getEmailSentAt(), now);
                if (daysSince < 1) daysSince = 1;

                String followUpDraftId = emailAutomationService.sendFollowUp(
                        target.getRecipientEmail(), target.getSubject(), daysSince,
                        target.getCompanyName(), target.getJobDescription());

                target.setStatus("FOLLOW_UP_DRAFT_CREATED");
                target.setGmailDraftId(followUpDraftId); // overwrite with follow-up draft ID
                targetRepository.save(target);
                log.debug("Follow-up draft created for {}", target.getCompanyName());
                log.info("Follow-up draft created for target ID: {}", target.getId());
            } catch (Exception e) {
                target.setStatus("FAILED");
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
            target.setStatus("PENDING");
            target.setErrorReason("Retry " + (retries + 1) + "/" + MAX_RETRIES + ": " + e.getMessage());
            targetRepository.save(target);
            log.debug("Transient failure for {} (retry {}/{}): {}",
                    target.getCompanyName(), retries + 1, MAX_RETRIES, e.getMessage());
            log.warn("Transient failure for target ID: {} (retry {}/{}): {}",
                    target.getId(), retries + 1, MAX_RETRIES, e.getMessage());
        } else {
            target.setStatus("FAILED");
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
}
