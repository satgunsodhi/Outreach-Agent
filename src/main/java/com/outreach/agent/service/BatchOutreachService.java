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

    public BatchOutreachService(OutreachTargetRepository targetRepository,
                                CompanyResearchAgent researchAgent,
                                ResumeOrchestrationService resumeOrchestrationService,
                                CoverLetterAgent coverLetterAgent,
                                EmailAutomationService emailAutomationService,
                                MasterResumeService masterResumeService,
                                ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.researchAgent = researchAgent;
        this.resumeOrchestrationService = resumeOrchestrationService;
        this.coverLetterAgent = coverLetterAgent;
        this.emailAutomationService = emailAutomationService;
        this.masterResumeService = masterResumeService;
        this.objectMapper = objectMapper;
    }

    // Run every 5 minutes to draft targets
    @Scheduled(fixedDelay = 300000)
    public void processPendingTargets() {
        List<OutreachTarget> pendingTargets = targetRepository.findByStatus("PENDING");

        for (OutreachTarget target : pendingTargets) {
            try {
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

                // 3. Generate Cover Letter
                String masterResumeJson = objectMapper.writeValueAsString(masterResumeService.getMasterResume());
                String coverLetterBody = coverLetterAgent.generateCoverLetter(masterResumeJson, jd, companyResearch);

                String subject = target.getSubject() != null && !target.getSubject().isBlank()
                        ? target.getSubject()
                        : "Application for Role at " + target.getCompanyName() + " - Satgun Singh Sodhi";

                // 4. Update State to DRAFTED
                target.setGeneratedPdfPath(pdfPathStr);
                target.setDraftedCoverLetter(coverLetterBody);
                target.setSubject(subject);
                target.setStatus("DRAFTED");
                target.setEmailScheduledAt(calculateNextWorkingDay8AmIst());
                targetRepository.save(target);

            } catch (Exception e) {
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

        for (OutreachTarget target : dueEmails) {
            try {
                byte[] resumePdf = Files.readAllBytes(Path.of(target.getGeneratedPdfPath()));
                
                emailAutomationService.sendResumeEmail(
                    target.getRecipientEmail(), 
                    target.getSubject(), 
                    resumePdf, 
                    target.getDraftedCoverLetter()
                );

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
}
