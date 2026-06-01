package com.outreach.agent.controller;

import com.outreach.agent.dto.ResumeRequest;
import com.outreach.agent.dto.ResumeResponse;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.repository.OutreachTargetRepository;
import com.outreach.agent.service.GmailService;
import com.outreach.agent.service.ResumeOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeOrchestrationService orchestrationService;
    private final OutreachTargetRepository targetRepository;
    private final GmailService gmailService;

    public ResumeController(ResumeOrchestrationService orchestrationService,
                            OutreachTargetRepository targetRepository,
                            GmailService gmailService) {
        this.orchestrationService = orchestrationService;
        this.targetRepository = targetRepository;
        this.gmailService = gmailService;
    }

    @DeleteMapping("/clean-targets")
    public ResponseEntity<String> cleanTargetsByKeyword(@RequestParam String keyword) {
        try {
            int deletedCount = 0;
            List<OutreachTarget> targets = targetRepository.findAll();
            for (OutreachTarget target : targets) {
                if (targetMatchesKeyword(target, keyword)) {
                    // Delete Gmail Draft
                    if (target.getGmailDraftId() != null) {
                        gmailService.deleteDraft(target.getGmailDraftId());
                    }
                    // Delete PDF file
                    deleteGeneratedPdf(target);
                    // Delete from DB
                    targetRepository.delete(target);
                    deletedCount++;
                }
            }
            return ResponseEntity.ok("Deleted " + deletedCount + " targets (including drafts and PDFs) containing the keyword: " + keyword);
        } catch (Exception e) {
            log.error("Error deleting targets by keyword '{}': {}", keyword, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error deleting targets: " + e.getMessage());
        }
    }

    @PostMapping("/reset-targets")
    public ResponseEntity<String> resetTargetsByKeyword(@RequestParam String keyword) {
        try {
            int resetCount = 0;
            List<OutreachTarget> targets = targetRepository.findAll();
            for (OutreachTarget target : targets) {
                if (targetMatchesKeyword(target, keyword)) {
                    // Delete Gmail Draft
                    if (target.getGmailDraftId() != null) {
                        gmailService.deleteDraft(target.getGmailDraftId());
                    }
                    // Delete PDF file
                    deleteGeneratedPdf(target);

                    // Reset to PENDING and clear all derived fields
                    target.setStatus(TargetStatus.PENDING);
                    target.setErrorReason(null);
                    target.setSubject(null);
                    target.setDraftedCoverLetter(null);
                    target.setGeneratedPdfPath(null);
                    target.setGmailDraftId(null);
                    target.setEmailScheduledAt(null);
                    target.setEmailSentAt(null);
                    target.setDraftCreatedAt(null);
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
            log.error("Error resetting targets by keyword '{}': {}", keyword, e.getMessage(), e);
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
            log.error("Error generating resume: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ResumeResponse(null, "Error generating resume: " + e.getMessage()));
        }
    }

    /**
     * Shared keyword-matching logic for both clean and reset endpoints.
     * Checks the cover letter text first (cheap), then falls back to parsing the PDF (expensive).
     * Extracted here to eliminate 60+ lines of duplication between the two endpoints.
     */
    private boolean targetMatchesKeyword(OutreachTarget target, String keyword) {
        if (target.getDraftedCoverLetter() != null
                && target.getDraftedCoverLetter().toLowerCase().contains(keyword.toLowerCase())) {
            return true;
        }
        if (target.getGeneratedPdfPath() != null) {
            java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
            if (pdfFile.exists()) {
                try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                    org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    String pdfText = stripper.getText(document);
                    return pdfText != null && pdfText.toLowerCase().contains(keyword.toLowerCase());
                } catch (Exception e) {
                    log.warn("Could not parse PDF at '{}' for keyword match: {}", target.getGeneratedPdfPath(), e.getMessage());
                }
            }
        }
        return false;
    }

    /** Deletes the generated PDF file from disk if it exists. */
    private void deleteGeneratedPdf(OutreachTarget target) {
        if (target.getGeneratedPdfPath() != null) {
            java.io.File pdfFile = new java.io.File(target.getGeneratedPdfPath());
            if (pdfFile.exists() && !pdfFile.delete()) {
                log.warn("Could not delete PDF file at: {}", target.getGeneratedPdfPath());
            }
        }
    }
}
