package com.outreach.agent.service;

import org.springframework.lang.NonNull;

public interface EmailAutomationService {
    void sendResumeEmail(
            @NonNull String recipientEmail,
            @NonNull String subject,
            byte[] resumePdf,
            @NonNull String coverLetterBody);

    void sendFollowUp(
            @NonNull String recipientEmail,
            @NonNull String originalSubject,
            int daysSinceSent);
}
