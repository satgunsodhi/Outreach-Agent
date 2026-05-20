package com.outreach.agent.service;

import com.outreach.agent.agent.ResumeAgent;
import org.springframework.stereotype.Service;

@Service
public class ResumeOrchestrationService {

    private final ResumeAgent resumeAgent;

    public ResumeOrchestrationService(ResumeAgent resumeAgent) {
        this.resumeAgent = resumeAgent;
    }

    public String generateTailoredResume(String jobDescription) {
        return resumeAgent.tailorResume(jobDescription);
    }
}
