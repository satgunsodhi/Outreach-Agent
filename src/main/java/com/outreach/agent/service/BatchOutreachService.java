package com.outreach.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.agent.CompanyResearchAgent;
import com.outreach.agent.agent.CoverLetterAgent;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;

@Service
public class BatchOutreachService {

    private final OutreachTargetRepository targetRepository;
    private final CompanyResearchAgent researchAgent;
    private final ResumeOrchestrationService resumeOrchestrationService;
    private final CoverLetterAgent coverLetterAgent;
    private final EmailAutomationService emailAutomationService;
    private final MasterResumeService masterResumeService;
    private final ObjectMapper objectMapper;
    private final GoogleDriveService googleDriveService;

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

    // Run every 10 seconds to draft targets
    @Scheduled(fixedDelay = 10000)
    public void processPendingTargets() {
        // FOR TESTING ONLY: Only grab targets that go to your personal email!
        List<OutreachTarget> pendingTargets = targetRepository.findByStatus("PENDING").stream()
                .filter(t -> "satgunsodhi@gmail.com".equalsIgnoreCase(t.getRecipientEmail()))
                .toList();

        if (!pendingTargets.isEmpty()) {
            System.out.println("[BatchOutreachService] Found " + pendingTargets.size() + " pending test targets to process.");
        }

        for (OutreachTarget target : pendingTargets) {
            try {
                System.out.println("[BatchOutreachService] Starting processing for target: " + target.getCompanyName());
                target.setStatus("PROCESSING");
                targetRepository.save(target);

                // 1. Research Company
                String companyResearch = "No specific research provided.";
                if (target.getJobUrl() != null && !target.getJobUrl().isBlank()) {
                    companyResearch = researchAgent.researchCompany(target.getJobUrl());
                } else if (target.getCompanyName() != null && !target.getCompanyName().isBlank()) {
                    companyResearch = researchAgent.researchCompany(target.getCompanyName());
                }

                // 2. Generate Tailored Resume (PDF)
                String jd = target.getJobDescription() != null && !target.getJobDescription().isBlank()
                        ? target.getJobDescription()
                        : "General position at " + target.getCompanyName();
                
                String pdfPathStr = resumeOrchestrationService.generateTailoredResume(jd);

                // 3. Generate Cover Letter & Subject
                String masterResumeJson = objectMapper.writeValueAsString(masterResumeService.getMasterResume());
                String coverLetterBody = coverLetterAgent.generateCoverLetter(masterResumeJson, jd, companyResearch);

                // Upload resume to Google Drive and append link to cover letter
                String driveLink = googleDriveService.uploadResume(pdfPathStr);
                coverLetterBody = coverLetterBody.trim() + "\n\nResume Link: " + driveLink;
                
                String dynamicSubject = coverLetterAgent.generateSubject(target.getCompanyName(), jd);

                String subject = target.getSubject() != null && !target.getSubject().isBlank()
                        ? target.getSubject()
                        : dynamicSubject;

                String candidateName = masterResumeService.getMasterResume().personalInfo().name();
                coverLetterBody = fillPlaceholders(coverLetterBody, candidateName, target.getCompanyName());
                subject = fillPlaceholders(subject, candidateName, target.getCompanyName());

                if (containsPlaceholderTokens(coverLetterBody) || containsPlaceholderTokens(subject)) {
                    throw new IllegalStateException("Generated outreach text still contains placeholder tokens");
                }

                // 4. Update State to DRAFTED
                target.setGeneratedPdfPath(pdfPathStr);
                target.setDraftedCoverLetter(coverLetterBody);
                target.setSubject(subject);
                target.setStatus("DRAFTED");
                
                if ("satgunsodhi@gmail.com".equalsIgnoreCase(target.getRecipientEmail())) {
                    // Send tests immediately
                    target.setEmailScheduledAt(LocalDateTime.now().minusMinutes(5));
                } else {
                    // Safety fallback: standard 8am scheduling
                    target.setEmailScheduledAt(calculateNextWorkingDay8AmIst());
                }
                
                targetRepository.save(target);
                System.out.println("[BatchOutreachService] Successfully drafted target: " + target.getCompanyName() + ". PDF saved, status set to DRAFTED.");

            } catch (Exception e) {
                System.err.println("[BatchOutreachService] Failed to process target " + target.getCompanyName() + ": " + e.getMessage());
                e.printStackTrace();
                target.setStatus("FAILED");
                target.setErrorReason(e.getMessage());
                targetRepository.save(target);
            }
        }
    }

    // Run every minute to check if drafted targets are ready to send
    @Scheduled(fixedDelay = 60000)
    public void dispatchDraftedTargets() {
        LocalDateTime now = LocalDateTime.now();
        List<OutreachTarget> dueEmails = targetRepository.findByStatusAndEmailScheduledAtBefore("DRAFTED", now);

        if (!dueEmails.isEmpty()) {
            System.out.println("[BatchOutreachService] Found " + dueEmails.size() + " due emails to dispatch.");
        }

        for (OutreachTarget target : dueEmails) {
            try {
                System.out.println("[BatchOutreachService] Dispatching email to " + target.getRecipientEmail() + " for company " + target.getCompanyName());
                
                emailAutomationService.sendResumeEmail(
                    target.getRecipientEmail(), 
                    target.getSubject(), 
                    null, // Do not attach resume as PDF, it is linked via Google Drive in cover letter
                    target.getDraftedCoverLetter()
                );

                // Clean up the PDF file after successful dispatch to save disk space
                try {
                    Files.deleteIfExists(Path.of(target.getGeneratedPdfPath()));
                } catch (Exception ex) {
                    System.err.println("Failed to delete PDF: " + target.getGeneratedPdfPath());
                }

                target.setStatus("EMAIL_SENT");
                target.setEmailSentAt(LocalDateTime.now());
                target.setFollowUpScheduledAt(calculateNextWorkingDay8AmIst()); // Schedules follow-up for next working day
                targetRepository.save(target);
            } catch (Exception e) {
                target.setStatus("FAILED");
                target.setErrorReason("Dispatch failed: " + e.getMessage());
                targetRepository.save(target);
            }
        }
    }

    // Run every hour to check for follow-ups
    @Scheduled(fixedDelay = 3600000)
    public void processFollowUps() {
        LocalDateTime now = LocalDateTime.now();
        List<OutreachTarget> dueFollowUps = targetRepository.findByStatusAndFollowUpScheduledAtBefore("EMAIL_SENT", now);

        for (OutreachTarget target : dueFollowUps) {
            try {
                int daysSince = (int) java.time.temporal.ChronoUnit.DAYS.between(target.getEmailSentAt(), now);
                if (daysSince < 1) daysSince = 1;

                emailAutomationService.sendFollowUp(target.getRecipientEmail(), target.getSubject(), daysSince);
                
                target.setStatus("FOLLOW_UP_SENT");
                targetRepository.save(target);
            } catch (Exception e) {
                target.setStatus("FAILED");
                target.setErrorReason("Follow up failed: " + e.getMessage());
                targetRepository.save(target);
            }
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

    private String fillPlaceholders(String text, String candidateName, String companyName) {
        if (text == null) {
            return null;
        }

        return text
                .replace("[Your Name]", candidateName)
                .replace("<PRIVATE_PERSON>", candidateName)
                .replace("YOUR_NAME", candidateName)
                .replace("{name}", candidateName)
                .replace("[Company]", companyName)
                .replace("[Company Name]", companyName)
                .replace("{companyName}", companyName)
                .replace("<COMPANY_NAME>", companyName);
    }

    private boolean containsPlaceholderTokens(String text) {
        if (text == null) {
            return false;
        }

        String upper = text.toUpperCase();
        return upper.contains("YOUR_NAME")
                || upper.contains("YOUR COMPANY")
                || upper.contains("[YOUR NAME]")
                || upper.contains("[COMPANY NAME]")
                || upper.contains("<PRIVATE_PERSON>")
                || upper.contains("<COMPANY_NAME>")
                || upper.contains("{NAME}")
                || upper.contains("{COMPANYNAME}");
    }
}
