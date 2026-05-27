package com.outreach.agent.controller;

import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private final OutreachTargetRepository repository;

    public TargetController(OutreachTargetRepository repository) {
        this.repository = repository;
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

        newTarget.setStatus("PENDING");
        OutreachTarget saved = repository.save(newTarget);
        return ResponseEntity.ok(saved);
    }
}
