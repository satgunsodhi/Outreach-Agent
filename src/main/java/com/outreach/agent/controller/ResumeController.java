package com.outreach.agent.controller;

import com.outreach.agent.dto.ResumeRequest;
import com.outreach.agent.dto.ResumeResponse;
import com.outreach.agent.service.ResumeOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeOrchestrationService orchestrationService;
    private final com.outreach.agent.repository.OutreachTargetRepository targetRepository;
    private final com.outreach.agent.service.GmailService gmailService;

    public ResumeController(ResumeOrchestrationService orchestrationService, 
                            com.outreach.agent.repository.OutreachTargetRepository targetRepository,
                            com.outreach.agent.service.GmailService gmailService) {
        this.orchestrationService = orchestrationService;
        this.targetRepository = targetRepository;
        this.gmailService = gmailService;
    }

    @DeleteMapping("/clean-targets")
    public ResponseEntity<String> cleanTargetsByKeyword(@RequestParam String keyword) {
        try {
            int deletedCount = 0;
            java.util.List<com.outreach.agent.model.OutreachTarget> targets = targetRepository.findAll();
            for (com.outreach.agent.model.OutreachTarget target : targets) {
                boolean match = false;
                if (target.getDraftedCoverLetter() != null && target.getDraftedCoverLetter().toLowerCase().contains(keyword.toLowerCase())) {
                    match = true;
                } else if (target.getGeneratedPdfPath() != null) {
                    java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
                    if (pdfFile.exists()) {
                        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                            String pdfText = stripper.getText(document);
                            if (pdfText != null && pdfText.toLowerCase().contains(keyword.toLowerCase())) {
                                match = true;
                            }
                        } catch (Exception e) {
                            // ignore pdf parse error
                        }
                    }
                }

                if (match) {
                    // Delete Gmail Draft
                    if (target.getGmailDraftId() != null) {
                        gmailService.deleteDraft(target.getGmailDraftId());
                    }
                    // Delete PDF file
                    if (target.getGeneratedPdfPath() != null) {
                        java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
                        if (pdfFile.exists()) {
                            pdfFile.delete();
                        }
                    }
                    // Delete from DB
                    targetRepository.delete(target);
                    deletedCount++;
                }
            }
            return ResponseEntity.ok("Deleted " + deletedCount + " targets (including drafts and PDFs) containing the keyword: " + keyword);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error deleting targets: " + e.getMessage());
        }
    }

    @PostMapping("/reset-targets")
    public ResponseEntity<String> resetTargetsByKeyword(@RequestParam String keyword) {
        try {
            int resetCount = 0;
            java.util.List<com.outreach.agent.model.OutreachTarget> targets = targetRepository.findAll();
            for (com.outreach.agent.model.OutreachTarget target : targets) {
                boolean match = false;
                if (target.getDraftedCoverLetter() != null && target.getDraftedCoverLetter().toLowerCase().contains(keyword.toLowerCase())) {
                    match = true;
                } else if (target.getGeneratedPdfPath() != null) {
                    java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
                    if (pdfFile.exists()) {
                        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                            String pdfText = stripper.getText(document);
                            if (pdfText != null && pdfText.toLowerCase().contains(keyword.toLowerCase())) {
                                match = true;
                            }
                        } catch (Exception e) {
                            // ignore pdf parse error
                        }
                    }
                }

                if (match) {
                    // Delete Gmail Draft
                    if (target.getGmailDraftId() != null) {
                        gmailService.deleteDraft(target.getGmailDraftId());
                    }
                    // Delete PDF file
                    if (target.getGeneratedPdfPath() != null) {
                        java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
                        if (pdfFile.exists()) {
                            pdfFile.delete();
                        }
                    }
                    
                    // Reset to PENDING and clear other fields
                    target.setStatus(com.outreach.agent.model.TargetStatus.PENDING);
                    target.setErrorReason(null);
                    target.setSubject(null);
                    target.setDraftedCoverLetter(null);
                    target.setGeneratedPdfPath(null);
                    target.setGmailDraftId(null);
                    target.setEmailScheduledAt(null);
                    target.setEmailSentAt(null);
                    target.setFollowUpScheduledAt(null);
                    target.setProcessingStartedAt(null);
                    target.setProcessingCompletedAt(null);
                    target.setRetryCount(0);
                    
                    targetRepository.save(target);
                    resetCount++;
                }
            }
            return ResponseEntity.ok("Reset " + resetCount + " targets (including drafts and PDFs deleted) containing the keyword: " + keyword);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error resetting targets: " + e.getMessage());
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<ResumeResponse> generateResume(@RequestBody ResumeRequest request) {
        try {
            String pdfPath = orchestrationService.generateTailoredResume(request.getJobDescription(), request.getCompanyResearch());
            ResumeResponse response = new ResumeResponse(pdfPath, "Resume generated successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(new ResumeResponse(null, "Error generating resume: " + e.getMessage()));
        }
    }
}
