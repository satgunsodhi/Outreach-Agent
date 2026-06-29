package com.outreach.agent.controller;

import com.outreach.agent.dto.BatchOutreachRequest;
import com.outreach.agent.dto.TargetDto;
import com.outreach.agent.model.CampaignStatus;
import com.outreach.agent.model.OutreachCampaign;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;
import com.outreach.agent.repository.OutreachCampaignRepository;
import com.outreach.agent.repository.OutreachTargetRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outreach")
public class BatchOutreachController {

    private final OutreachCampaignRepository campaignRepository;
    private final OutreachTargetRepository targetRepository;

    public BatchOutreachController(OutreachCampaignRepository campaignRepository, OutreachTargetRepository targetRepository) {
        this.campaignRepository = campaignRepository;
        this.targetRepository = targetRepository;
    }

    @GetMapping("/targets")
    public ResponseEntity<?> getAllTargets() {
        return ResponseEntity.ok(targetRepository.findAll());
    }

    @PostMapping("/reset-tests")
    public ResponseEntity<?> resetTestTargets() {
        List<OutreachTarget> testTargets = targetRepository.findAll().stream()
                .filter(t -> "your_test_email@gmail.com".equalsIgnoreCase(t.getRecipientEmail()))
                .toList();
                
        for (OutreachTarget target : testTargets) {
            target.setStatus(TargetStatus.PENDING);
            target.setClaimToken(null);
            target.setRetryCount(0);
            target.setErrorReason(null);
            target.setDraftedCoverLetter(null);
            target.setSubject(null);
            target.setGeneratedPdfPath(null);
            target.setEmailScheduledAt(null);
            target.setEmailSentAt(null);
            targetRepository.save(target);
        }
        
        return ResponseEntity.ok(java.util.Map.of("message", "Reset " + testTargets.size() + " test targets to PENDING."));
    }

    @PostMapping("/batch")
    public ResponseEntity<?> startBatchOutreach(@RequestBody BatchOutreachRequest request) {
        if (request.getTargets() == null || request.getTargets().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Targets list cannot be empty"));
        }

        OutreachCampaign campaign = new OutreachCampaign();
        campaign.setName(request.getCampaignName() != null ? request.getCampaignName() : "Batch Outreach");
        campaign.setStatus(CampaignStatus.ACTIVE);
        campaignRepository.save(campaign);

        long addedCount = 0;
        for (TargetDto dto : request.getTargets()) {
            if (targetRepository.existsByCompanyNameAndRecipientEmail(dto.getCompanyName(), dto.getRecipientEmail())) {
                continue; // Skip duplicate targets
            }
            OutreachTarget target = new OutreachTarget();
            target.setCampaign(campaign);
            target.setCompanyName(dto.getCompanyName());
            target.setRecipientEmail(dto.getRecipientEmail());
            target.setJobUrl(dto.getJobUrl());
            target.setJobDescription(dto.getJobDescription());
            target.setStatus(TargetStatus.PENDING);
            targetRepository.save(target);
            addedCount++;
        }

        // Fix A: clean up the campaign record if every target was a duplicate
        if (addedCount == 0) {
            campaignRepository.delete(campaign);
            return ResponseEntity.ok(Map.of(
                    "message", "All targets already exist — no new targets were added.",
                    "totalTargets", 0
            ));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Batch outreach scheduled successfully.",
                "campaignId", campaign.getId(),
                "totalTargets", addedCount
        ));
    }

    @GetMapping("/campaign/{id}")
    public ResponseEntity<?> getCampaignStatus(@PathVariable Long id) {
        return campaignRepository.findById(id).map(campaign -> {
            // Use a DB-side aggregation instead of findAll() + stream to avoid a full table scan and N+1 lazy loads.
            List<Object[]> rows = targetRepository.countByStatusForCampaign(id);
            long total = 0, pending = 0, processing = 0, drafted = 0, failed = 0;
            for (Object[] row : rows) {
                TargetStatus status = (TargetStatus) row[0];
                long count = ((Number) row[1]).longValue();
                total += count;
                switch (status) {
                    case PENDING -> pending = count;
                    case PROCESSING -> processing = count;
                    case DRAFT_CREATED, FOLLOW_UP_DRAFT_CREATED -> drafted += count;
                    case FAILED -> failed = count;
                }
            }
            return ResponseEntity.ok(Map.of(
                    "campaignId", campaign.getId(),
                    "name", campaign.getName(),
                    "status", campaign.getStatus(),
                    "stats", Map.of(
                            "total", total,
                            "pending", pending,
                            "processing", processing,
                            "drafted", drafted,
                            "failed", failed
                    )
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
