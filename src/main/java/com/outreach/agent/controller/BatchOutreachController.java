package com.outreach.agent.controller;

import com.outreach.agent.dto.BatchOutreachRequest;
import com.outreach.agent.dto.TargetDto;
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
                .filter(t -> "satgunsodhi@gmail.com".equalsIgnoreCase(t.getRecipientEmail()))
                .toList();
                
        for (OutreachTarget target : testTargets) {
            target.setStatus(TargetStatus.PENDING);
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
        campaign.setStatus("ACTIVE");
        campaignRepository.save(campaign);

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
        }

        long addedCount = targetRepository.findAll().stream()
                .filter(t -> t.getCampaign() != null && t.getCampaign().getId().equals(campaign.getId()))
                .count();

        return ResponseEntity.ok(Map.of(
                "message", "Batch outreach scheduled successfully.",
                "campaignId", campaign.getId(),
                "totalTargets", addedCount
        ));
    }

    @GetMapping("/campaign/{id}")
    public ResponseEntity<?> getCampaignStatus(@PathVariable Long id) {
        return campaignRepository.findById(id).map(campaign -> {
            List<OutreachTarget> targets = targetRepository.findAll()
                    .stream()
                    .filter(t -> t.getCampaign().getId().equals(id))
                    .toList();
            
            long pending = targets.stream().filter(t -> TargetStatus.PENDING.equals(t.getStatus())).count();
            long processing = targets.stream().filter(t -> TargetStatus.PROCESSING.equals(t.getStatus())).count();
            long drafted = targets.stream().filter(t -> TargetStatus.DRAFT_CREATED.equals(t.getStatus())).count();
            long failed = targets.stream().filter(t -> TargetStatus.FAILED.equals(t.getStatus())).count();

            return ResponseEntity.ok(Map.of(
                    "campaignId", campaign.getId(),
                    "name", campaign.getName(),
                    "status", campaign.getStatus(),
                    "stats", Map.of(
                            "total", targets.size(),
                            "pending", pending,
                            "processing", processing,
                            "drafted", drafted,
                            "failed", failed
                    )
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
