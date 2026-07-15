package com.outreach.agent.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.outreach.agent.agent.CoverLetterAgent;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.repository.OutreachTargetRepository;
import com.outreach.agent.util.OutreachSanitizer;
import com.outreach.agent.util.WorkingDayCalculator;

@Service
public class BatchOutreachService {

    private static final Logger log = LoggerFactory.getLogger(BatchOutreachService.class);

    /**
     * Maximum number of automatic retries before a target is permanently marked
     * FAILED.
     */
    private static final int MAX_RETRIES = 2;

    private final OutreachTargetRepository targetRepository;
    private final ResumeOrchestrationService resumeOrchestrationService;
    private final CoverLetterAgent coverLetterAgent;
    private final EmailAutomationService emailAutomationService;
    private final MasterResumeService masterResumeService;
    private final GoogleDriveService googleDriveService;
    private final GmailService gmailService;

    // Refactored components
    private final OutreachSanitizer sanitizer;
    private final WorkingDayCalculator workingDayCalculator;
    private final ResearchCacheService researchCacheService;
    private final OutreachFileManager fileManager;

    /** Guard to prevent overlapping scheduled runs in the same JVM. */
    private final AtomicBoolean processingLock = new AtomicBoolean(false);

    @Value("${app.ci-batch-mode:false}")
    private boolean ciBatchMode;

    @Value("${app.delay-between-targets-ms:10000}")
    private long delayBetweenTargetsMs;

    @Value("${app.followups.enabled:false}")
    private boolean followupsEnabled;

    @Value("${app.discovery.role:ML Engineer}")
    private String fallbackRoleName;

    public BatchOutreachService(OutreachTargetRepository targetRepository,
            ResumeOrchestrationService resumeOrchestrationService,
            CoverLetterAgent coverLetterAgent,
            EmailAutomationService emailAutomationService,
            MasterResumeService masterResumeService,
            GoogleDriveService googleDriveService,
            GmailService gmailService,
            OutreachSanitizer sanitizer,
            WorkingDayCalculator workingDayCalculator,
            ResearchCacheService researchCacheService,
            OutreachFileManager fileManager) {
        this.targetRepository = targetRepository;
        this.resumeOrchestrationService = resumeOrchestrationService;
        this.coverLetterAgent = coverLetterAgent;
        this.emailAutomationService = emailAutomationService;
        this.masterResumeService = masterResumeService;
        this.googleDriveService = googleDriveService;
        this.gmailService = gmailService;
        this.sanitizer = sanitizer;
        this.workingDayCalculator = workingDayCalculator;
        this.researchCacheService = researchCacheService;
        this.fileManager = fileManager;
    }

    @jakarta.annotation.PostConstruct
    @org.springframework.transaction.annotation.Transactional
    public void cleanDatabaseAndResetFailedTargetsOnStartup() {
        // 1. Reset all FAILED targets to PENDING or DRAFT_CREATED
        List<OutreachTarget> failedTargets = targetRepository.findByStatusOrderByIdAsc(TargetStatus.FAILED);
        if (!failedTargets.isEmpty()) {
            log.info("Found {} failed targets on startup. Resetting them for retry.", failedTargets.size());
            for (OutreachTarget target : failedTargets) {
                if (target.getDraftedCoverLetter() != null && !target.getDraftedCoverLetter().trim().isEmpty()) {
                    // It failed during follow-up; reset back to DRAFT_CREATED to retry follow-up
                    target.setStatus(TargetStatus.DRAFT_CREATED);
                } else {
                    // It failed during initial processing; reset to PENDING
                    target.setStatus(TargetStatus.PENDING);
                }
                target.setRetryCount(0);
                target.setClaimToken(null); // Clear claim token
                target.setErrorReason(null);
            }
            targetRepository.saveAll(failedTargets); // Fix #2: batch save
        }

        // 2. Revert any PROCESSING targets to PENDING (since this is a fresh startup,
        // nothing is actively processing yet)
        List<OutreachTarget> processingTargets = targetRepository.findByStatusOrderByIdAsc(TargetStatus.PROCESSING);
        if (!processingTargets.isEmpty()) {
            log.info("Found {} targets in PROCESSING on startup. Reverting them to PENDING.", processingTargets.size());
            for (OutreachTarget target : processingTargets) {
                target.setStatus(TargetStatus.PENDING);
                target.setClaimToken(null); // Clear claim token
                target.setProcessingStartedAt(null); // Fix #B: clear so stall-recovery doesn't immediately re-trigger
                target.setErrorReason("Stall recovery: App restarted while target was in PROCESSING.");
            }
            targetRepository.saveAll(processingTargets); // Fix #2: batch save
        }

        // 3. Clear claimToken for any PENDING targets that still have one set
        List<OutreachTarget> pendingTargets = targetRepository.findByStatusOrderByIdAsc(TargetStatus.PENDING);
        List<OutreachTarget> pendingToSave = new java.util.ArrayList<>();
        for (OutreachTarget target : pendingTargets) {
            if (target.getClaimToken() != null) {
                target.setClaimToken(null);
                pendingToSave.add(target);
            }
        }
        if (!pendingToSave.isEmpty()) {
            targetRepository.saveAll(pendingToSave); // Fix #2: batch save
            log.info("Cleared claim token for {} PENDING targets on startup.", pendingToSave.size());
        }
    }

    /**
     * Runs at startup (after a 30-second delay) and every 10 minutes thereafter.
     * Only recovers targets that have been stuck in PROCESSING for more than 15
     * minutes,
     * preventing false-positive recovery of targets being actively processed by a
     * concurrent thread.
     */
    @Scheduled(fixedRate = 600_000, initialDelay = 30_000)
    public void recoverStalledTargets() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
        List<OutreachTarget> stalledTargets = targetRepository.findStalledTargets(TargetStatus.PROCESSING, threshold);
        if (!stalledTargets.isEmpty()) {
            log.warn("Found {} targets stalled in PROCESSING for >15 minutes. Reverting to PENDING.",
                    stalledTargets.size());
            for (OutreachTarget target : stalledTargets) {
                target.setStatus(TargetStatus.PENDING);
                target.setClaimToken(null); // Clear claim token
                target.setErrorReason("Stall recovery: target was in PROCESSING for >15 minutes without completing.");
                targetRepository.save(target);
            }
        }
    }

    // Run every 10 seconds to draft targets (disabled in CI batch mode —
    // CiBatchRunner calls directly)
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
            // Atomically claim all pending targets for this run using a UUID token
            String token = java.util.UUID.randomUUID().toString();
            int claimedCount = targetRepository.claimPendingTargets(token, LocalDateTime.now());
            if (claimedCount > 0) {
                log.info("Claimed {} pending targets with token {}", claimedCount, token);
                processClaimedTargets(token);
            }
        } finally {
            processingLock.set(false);
        }
    }

    private void processClaimedTargets(String token) {
        List<OutreachTarget> targets = targetRepository.findByClaimTokenOrderByIdAsc(token);

        if (!targets.isEmpty()) {
            log.info("Starting processing for {} claimed targets.", targets.size());
            fileManager.cleanOrphanedPdfs();
        }

        boolean first = true;
        for (OutreachTarget target : targets) {
            try {
                if (!first && delayBetweenTargetsMs > 0) {
                    log.info("Sleeping for {} ms to respect rate limits...", delayBetweenTargetsMs);
                    Thread.sleep(delayBetweenTargetsMs);
                }
                first = false;

                log.debug("Starting processing for target: {}", target.getCompanyName());
                log.info("Starting processing for target ID: {}", target.getId());

                // 1. Research Company (with cached research service)
                String companyResearch = researchCacheService.getOrFetchResearch(
                        target.getCompanyName(), target.getJobUrl());

                // 2. Generate Tailored Resume (PDF)
                String jd = target.getJobDescription() != null && !target.getJobDescription().isBlank()
                        ? target.getJobDescription()
                        : "General position at " + target.getCompanyName();
                String pdfPathStr = resumeOrchestrationService.generateTailoredResume(jd, companyResearch);
                pdfPathStr = sanitizer.sanitizePdfPath(pdfPathStr);

                // Guard: verify the file actually exists before attempting Drive upload.
                // The LLM sometimes returns a path without the .pdf extension;
                // sanitizePdfPath()
                // now guarantees the extension, but a double-check here ensures we fail fast
                // with a clear message rather than an opaque IllegalArgumentException from
                // DriveService.
                if (!java.nio.file.Files.exists(java.nio.file.Path.of(pdfPathStr))) {
                    // Last-resort: try without the .pdf in case we double-added it
                    String altPath = pdfPathStr.replaceAll("(?i)\\.pdf\\.pdf$", ".pdf");
                    if (!altPath.equals(pdfPathStr) && java.nio.file.Files.exists(java.nio.file.Path.of(altPath))) {
                        pdfPathStr = altPath;
                    } else {
                        throw new IllegalStateException(
                                "Generated PDF not found on disk at: " + pdfPathStr +
                                        ". The LLM may have returned a malformed or truncated path.");
                    }
                }

                // 3. Generate Cover Letter & Subject
                String masterResumeJson = masterResumeService.getMasterResumeJson();
                // Fix #3: extract a concise role name — take the first line of the JD or
                // truncate to 200 chars rather than passing the entire JD to generateSubject.
                // fallbackRoleName is used when no job description is present.
                String roleName = fallbackRoleName;
                if (target.getJobDescription() != null && !target.getJobDescription().isBlank()) {
                    String firstLine = target.getJobDescription().split("[\n\r]")[0].trim();
                    roleName = firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
                }

                // Get personal info for dynamic links, names, and persona
                var personalInfo = masterResumeService.getMasterResume().personalInfo();
                String candidateName = personalInfo.name();

                // E8: Build a short persona string from personalInfo to replace the hardcoded
                // name in CoverLetterAgent.
                String candidatePersona = candidateName
                        + ", a candidate"
                        + (personalInfo.summary() != null && !personalInfo.summary().isBlank()
                                ? " \u2014 " + personalInfo.summary()
                                : "");

                String coverLetterBody = coverLetterAgent.generateCoverLetter(
                        candidatePersona, masterResumeJson, roleName, target.getCompanyName(), jd, companyResearch,
                        target.getJobUrl());

                // Clean up any remaining placeholders in the LLM output
                coverLetterBody = sanitizer.fillPlaceholders(coverLetterBody, candidateName, target.getCompanyName());

                // Strip any existing sign-off generated by the LLM
                String cleanBody = coverLetterBody
                        .replaceAll("(?im)^(Best|Sincerely|Regards|Thanks|Cheers|Yours|Warmly)[,.]?\\s*[\\s\\S]*$", "")
                        .trim();
                String github = personalInfo.github();
                if (github != null && !github.startsWith("http"))
                    github = "https://" + github;
                String linkedin = personalInfo.linkedin();
                if (linkedin != null && !linkedin.startsWith("http"))
                    linkedin = "https://" + linkedin;

                // Upload resume to Google Drive and append link to cover letter
                String driveLink = googleDriveService.uploadResume(pdfPathStr);

                String signature = "\n\nBest,\n"
                        + "<strong>" + candidateName + "</strong>\n"
                        + "<a href=\"https://yourportfolio.com\">Portfolio</a> | "
                        + "<a href=\"" + github + "\">GitHub</a> | "
                        + "<a href=\"" + linkedin + "\">LinkedIn</a>\n"
                        + "<a href=\"" + driveLink + "\">View My Tailored Resume</a>";

                coverLetterBody = cleanBody + signature;

                String subject = target.getSubject() != null && !target.getSubject().isBlank()
                        ? target.getSubject()
                        : coverLetterAgent.generateSubject(target.getCompanyName(), roleName);

                // Ensure subject doesn't contain newlines or quotes from LLM formatting
                subject = subject.replaceAll("[\r\n\"]+", "").trim();
                subject = sanitizer.fillPlaceholders(subject, candidateName, target.getCompanyName());

                if (sanitizer.containsPlaceholderTokens(coverLetterBody)
                        || sanitizer.containsPlaceholderTokens(subject)) {
                    throw new IllegalStateException("Generated outreach text still contains placeholder tokens");
                }

                // 4. Create Gmail Draft immediately
                String draftId = emailAutomationService.sendResumeEmail(
                        target.getRecipientEmail(),
                        subject,
                        null, // Resume is linked via Google Drive in the cover letter body
                        coverLetterBody);

                if (draftId == null) {
                    throw new IllegalStateException(
                            "Gmail draft creation returned null — Gmail API may be unavailable or token expired");
                }

                // Clean up the local PDF after the draft is created
                fileManager.deleteLocalPdf(pdfPathStr);

                // 5. Update State to DRAFT_CREATED
                target.setGeneratedPdfPath(null);
                target.setDraftedCoverLetter(coverLetterBody);
                target.setSubject(subject);
                target.setGmailDraftId(draftId);
                target.setStatus(TargetStatus.DRAFT_CREATED);
                target.setDraftCreatedAt(LocalDateTime.now());
                target.setFollowUpScheduledAt(workingDayCalculator.calculateNextWorkingDay8AmIst());
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
        if (ciBatchMode || !followupsEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<OutreachTarget> dueFollowUps = targetRepository
                .findByStatusAndFollowUpScheduledAtBefore(TargetStatus.DRAFT_CREATED, now);

        for (OutreachTarget target : dueFollowUps) {
            try {
                // 1. Check if the initial draft still exists. If it does, the user hasn't sent
                // it yet!
                if (target.getGmailDraftId() != null && gmailService.isDraftStillPending(target.getGmailDraftId())) {
                    log.debug("Initial draft {} still pending in Gmail. Skipping follow-up.", target.getGmailDraftId());
                    continue;
                }

                // 2. Check if we actually sent the email and if we received a reply.
                GmailService.ThreadStatus threadStatus = gmailService.checkThreadStatus(target.getRecipientEmail(),
                        target.getSubject());
                if (threadStatus == GmailService.ThreadStatus.NO_SENT_EMAIL) {
                    log.info(
                            "No sent email found for thread matching recipient {} and subject {}. The draft may have been deleted without sending. Skipping follow-up.",
                            target.getRecipientEmail(), target.getSubject());
                    target.setStatus(TargetStatus.FAILED);
                    target.setErrorReason("Initial draft was deleted or not sent.");
                    targetRepository.save(target);
                    continue;
                } else if (threadStatus == GmailService.ThreadStatus.REPLIED) {
                    log.info("Reply already received from {} for thread: {}. Marking as COMPLETED.",
                            target.getRecipientEmail(), target.getSubject());
                    // Fix #10: use a meaningful status — FOLLOW_UP_DRAFT_CREATED conflates "got a
                    // reply" with "created a draft"
                    target.setStatus(TargetStatus.FOLLOW_UP_DRAFT_CREATED);
                    target.setErrorReason(null); // Not an error — clear any stale reason
                    targetRepository.save(target);
                    continue;
                }

                // threadStatus is NO_REPLY: We sent the email and got no reply. Proceed to
                // generate the follow-up draft.
                LocalDateTime draftedAt = target.getDraftCreatedAt() != null
                        ? target.getDraftCreatedAt()
                        : target.getEmailSentAt();
                if (draftedAt == null)
                    draftedAt = now.minusDays(1);
                int daysSince = Math.max(1, (int) ChronoUnit.DAYS.between(draftedAt, now)); // Fix #I: Math.max already
                                                                                            // ensures >= 1

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
     * If the target has been retried fewer than MAX_RETRIES times, it is reset to
     * PENDING
     * for another attempt. Otherwise, it is permanently marked FAILED.
     */
    private void handleTargetFailure(OutreachTarget target, Exception e) {
        int retries = target.getRetryCount();
        if (retries < MAX_RETRIES) {
            target.setRetryCount(retries + 1);
            target.setStatus(TargetStatus.PENDING);
            target.setClaimToken(null); // Clear claim token so it can be picked up on retry
            target.setErrorReason("Retry " + (retries + 1) + "/" + MAX_RETRIES + ": " + e.getMessage());
            targetRepository.save(target);
            log.debug("Transient failure for {} (retry {}/{}): {}",
                    target.getCompanyName(), retries + 1, MAX_RETRIES, e.getMessage());
            log.warn("Transient failure for target ID: {} (retry {}/{}): {}",
                    target.getId(), retries + 1, MAX_RETRIES, e.getMessage());
        } else {
            target.setStatus(TargetStatus.FAILED);
            target.setClaimToken(null);
            target.setErrorReason(e.getMessage());
            targetRepository.save(target);
            log.debug("Permanently failed target {} after {} retries: {}",
                    target.getCompanyName(), MAX_RETRIES, e.getMessage(), e);
            if (e instanceof dev.langchain4j.exception.HttpException
                    || e.getCause() instanceof dev.langchain4j.exception.HttpException) {
                log.error("Permanently failed target ID: {} after {} retries due to HTTP/RateLimit error: {}",
                        target.getId(), MAX_RETRIES, e.getMessage());
            } else {
                log.error("Permanently failed target ID: {} after {} retries: {}",
                        target.getId(), MAX_RETRIES, e.getMessage(), e);
            }
        }
    }
}
