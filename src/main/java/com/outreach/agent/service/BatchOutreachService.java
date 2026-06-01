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

    /** Maximum number of automatic retries before a target is permanently marked FAILED. */
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

    @Value("${app.followups.enabled:false}")
    private boolean followupsEnabled;

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

    /**
     * Runs at startup (after a 30-second delay) and every 10 minutes thereafter.
     * Only recovers targets that have been stuck in PROCESSING for more than 15 minutes,
     * preventing false-positive recovery of targets being actively processed by a concurrent thread.
     */
    @Scheduled(fixedRate = 600_000, initialDelay = 30_000)
    public void recoverStalledTargets() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
        List<OutreachTarget> stalledTargets = targetRepository.findStalledTargets(TargetStatus.PROCESSING, threshold);
        if (!stalledTargets.isEmpty()) {
            log.warn("Found {} targets stalled in PROCESSING for >15 minutes. Reverting to PENDING.", stalledTargets.size());
            for (OutreachTarget target : stalledTargets) {
                target.setStatus(TargetStatus.PENDING);
                target.setClaimToken(null); // Clear claim token
                target.setErrorReason("Stall recovery: target was in PROCESSING for >15 minutes without completing.");
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

        for (OutreachTarget target : targets) {
            try {
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

                // 3. Generate Cover Letter & Subject
                String masterResumeJson = masterResumeService.getMasterResumeJson();
                String roleName = target.getJobDescription() != null ? target.getJobDescription() : "ML Intern";
                String coverLetterBody = coverLetterAgent.generateCoverLetter(
                        masterResumeJson, roleName, target.getCompanyName(), jd, companyResearch, target.getJobUrl());
                
                // Get personal info for dynamic links and names
                var personalInfo = masterResumeService.getMasterResume().personalInfo();
                String candidateName = personalInfo.name();

                // Clean up any remaining placeholders in the LLM output
                coverLetterBody = sanitizer.fillPlaceholders(coverLetterBody, candidateName, target.getCompanyName());

                // Strip any existing sign-off generated by the LLM
                String cleanBody = coverLetterBody
                        .replaceAll("(?im)^(Best|Sincerely|Regards|Thanks|Cheers|Yours|Warmly)[,.]?\\s*[\\s\\S]*$", "")
                        .trim();
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
                subject = sanitizer.fillPlaceholders(subject, candidateName, target.getCompanyName());

                if (sanitizer.containsPlaceholderTokens(coverLetterBody) || sanitizer.containsPlaceholderTokens(subject)) {
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
                
                // Clean up the local PDF after the draft is created
                fileManager.deleteLocalPdf(pdfPathStr);

                // 5. Update State to DRAFT_CREATED
                target.setGeneratedPdfPath(null);
                target.setDraftedCoverLetter(coverLetterBody);
                target.setSubject(subject);
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
        List<OutreachTarget> dueFollowUps = targetRepository.findByStatusAndFollowUpScheduledAtBefore(TargetStatus.DRAFT_CREATED, now);

        for (OutreachTarget target : dueFollowUps) {
            try {
                // 1. Check if the initial draft still exists. If it does, the user hasn't sent it yet!
                if (target.getGmailDraftId() != null && gmailService.isDraftStillPending(target.getGmailDraftId())) {
                    log.debug("Initial draft {} still pending in Gmail. Skipping follow-up.", target.getGmailDraftId());
                    continue;
                }

                // 2. Check if we actually sent the email and if we received a reply.
                GmailService.ThreadStatus threadStatus = gmailService.checkThreadStatus(target.getRecipientEmail(), target.getSubject());
                if (threadStatus == GmailService.ThreadStatus.NO_SENT_EMAIL) {
                    log.info("No sent email found for thread matching recipient {} and subject {}. The draft may have been deleted without sending. Skipping follow-up.", target.getRecipientEmail(), target.getSubject());
                    target.setStatus(TargetStatus.FAILED);
                    target.setErrorReason("Initial draft was deleted or not sent.");
                    targetRepository.save(target);
                    continue;
                } else if (threadStatus == GmailService.ThreadStatus.REPLIED) {
                    log.info("Reply already received from {} for thread: {}. Skipping follow-up.", target.getRecipientEmail(), target.getSubject());
                    target.setStatus(TargetStatus.FOLLOW_UP_DRAFT_CREATED);
                    target.setErrorReason("Reply received from recipient; follow-up skipped.");
                    targetRepository.save(target);
                    continue;
                }

                // threadStatus is NO_REPLY: We sent the email and got no reply. Proceed to generate the follow-up draft.
                LocalDateTime draftedAt = target.getDraftCreatedAt() != null
                        ? target.getDraftCreatedAt()
                        : target.getEmailSentAt();
                if (draftedAt == null) draftedAt = now.minusDays(1);
                int daysSince = Math.max(1, (int) ChronoUnit.DAYS.between(draftedAt, now));
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
            log.error("Permanently failed target ID: {} after {} retries: {}",
                    target.getId(), MAX_RETRIES, e.getMessage(), e);
        }
    }
}
