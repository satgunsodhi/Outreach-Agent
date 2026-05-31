package com.outreach.agent.service;

import org.springframework.lang.NonNull;

/**
 * Abstraction for automated outreach email handling.
 * <p>
 * The current implementation ({@link impl.EmailAutomationServiceImpl}) creates
 * <strong>Gmail Drafts</strong> via the Gmail API rather than sending emails directly.
 * Methods return the Gmail Draft ID so callers can store it for traceability.
 * </p>
 */
public interface EmailAutomationService {

    /**
     * Creates a Gmail Draft for the initial outreach email.
     *
     * @param recipientEmail target recipient address
     * @param subject        email subject line
     * @param resumePdf      optional PDF bytes (not used — resume is linked via Google Drive)
     * @param coverLetterBody plain-text email body
     * @return the Gmail Draft ID, or {@code null} if draft creation failed
     */
    String sendResumeEmail(
            @NonNull String recipientEmail,
            @NonNull String subject,
            byte[] resumePdf,
            @NonNull String coverLetterBody);

    /**
     * Creates a Gmail Draft for a follow-up email.
     *
     * @param recipientEmail  target recipient address
     * @param originalSubject subject of the original outreach email (will be prefixed with "Re: ")
     * @param daysSinceSent   days elapsed since the initial email — used in the body
     * @param companyName     company name for contextual follow-up
     * @param roleName        role name for contextual follow-up
     * @return the Gmail Draft ID, or {@code null} if draft creation failed
     */
    String sendFollowUp(
            @NonNull String recipientEmail,
            @NonNull String originalSubject,
            int daysSinceSent,
            String companyName,
            String roleName,
            String candidateName);
}
