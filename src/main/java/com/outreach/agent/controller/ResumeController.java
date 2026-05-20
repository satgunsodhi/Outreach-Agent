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

    public ResumeController(ResumeOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ResumeResponse> generateResume(@RequestBody ResumeRequest request) {
        try {
            String pdfPath = orchestrationService.generateTailoredResume(request.getJobDescription());
            ResumeResponse response = new ResumeResponse(pdfPath, "Resume generated successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(new ResumeResponse(null, "Error generating resume: " + e.getMessage()));
        }
    }
}
