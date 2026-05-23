package com.outreach.agent.service;

public interface EmailAutomationService {
    void sendResumeEmail(String recipientEmail, String subject, byte[] resumePdf, String coverLetterBody);

    void sendFollowUp(String recipientEmail, String originalSubject, int daysSinceSent);
}
