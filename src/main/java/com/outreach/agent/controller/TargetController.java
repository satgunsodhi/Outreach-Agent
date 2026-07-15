package com.outreach.agent.controller;

import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.repository.OutreachTargetRepository;
import com.outreach.agent.service.GmailService;
import com.outreach.agent.service.OutreachFileManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private final OutreachTargetRepository repository;
    private final GmailService gmailService;
    private final OutreachFileManager fileManager;

    public TargetController(OutreachTargetRepository repository,
                            GmailService gmailService,
                            OutreachFileManager fileManager) {
        this.repository = repository;
        this.gmailService = gmailService;
        this.fileManager = fileManager;
    }

    @GetMapping
    public List<OutreachTarget> getAllTargets() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> addTarget(@RequestBody OutreachTarget newTarget) {
        // Prevent duplicates
        boolean exists = repository.existsByCompanyNameAndRecipientEmail(
                newTarget.getCompanyName(), newTarget.getRecipientEmail());
        
        if (exists) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target with this company and email already exists."));
        }

        newTarget.setStatus(TargetStatus.PENDING);
        OutreachTarget saved = repository.save(newTarget);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTarget(@PathVariable Long id) {
        return repository.findById(id).map(target -> {
            try {
                // Delete Gmail Draft if it exists
                if (target.getGmailDraftId() != null) {
                    gmailService.deleteDraft(target.getGmailDraftId());
                }
                // Delete local PDF if it exists
                if (target.getGeneratedPdfPath() != null) {
                    fileManager.deleteLocalPdf(target.getGeneratedPdfPath());
                }
                // Delete from DB
                repository.delete(target);
                return ResponseEntity.ok(Map.of("message", "Target deleted successfully."));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Error deleting target: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reset")
    public ResponseEntity<?> resetTarget(@PathVariable Long id) {
        return repository.findById(id).map(target -> {
            try {
                // Delete Gmail Draft if it exists
                if (target.getGmailDraftId() != null) {
                    gmailService.deleteDraft(target.getGmailDraftId());
                }
                // Delete local PDF if it exists
                if (target.getGeneratedPdfPath() != null) {
                    fileManager.deleteLocalPdf(target.getGeneratedPdfPath());
                }

                // Reset to PENDING and clear derived fields
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
                target.setClaimToken(null);

                repository.save(target);
                return ResponseEntity.ok(target);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Error resetting target: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
