package com.outreach.agent.service.impl;

import com.outreach.agent.service.EmailAutomationService;
import com.outreach.agent.service.GmailService;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

/**
 * Email automation backed by the Gmail API (OAuth 2.0).
 * <p>
 * Instead of sending emails via SMTP, this implementation creates <strong>Gmail Drafts</strong>
 * in the authenticated user's mailbox. Drafts appear in the Gmail Drafts folder and can be
 * reviewed, edited, or sent manually.
 * </p>
 * The draft ID returned by the Gmail API is not used directly here — it is stored on the
 * {@code OutreachTarget} entity by {@code BatchOutreachService} for traceability.
 */
@Service
public class EmailAutomationServiceImpl implements EmailAutomationService {

    private final GmailService gmailService;

    public EmailAutomationServiceImpl(GmailService gmailService) {
        this.gmailService = gmailService;
    }

    /**
     * Creates a Gmail Draft for the outreach email.
     *
     * @return the Gmail Draft ID, or {@code null} if the draft could not be created
     */
    @Override
    public String sendResumeEmail(
            @NonNull String recipientEmail,
            @NonNull String subject,
            byte[] resumePdf,            // kept in interface for compatibility; not used (resume linked via Drive)
            @NonNull String coverLetterBody) {

        if (recipientEmail.isBlank()) throw new IllegalArgumentException("recipientEmail must not be blank");
        if (subject.isBlank())        throw new IllegalArgumentException("subject must not be blank");
        if (coverLetterBody.isBlank()) throw new IllegalArgumentException("coverLetterBody must not be blank");

        String draftId = gmailService.createDraft(recipientEmail, subject, coverLetterBody);
        if (draftId != null) {
            System.out.println("[EmailAutomationService] Draft created for " + recipientEmail + " (draftId=" + draftId + ")");
        } else {
            System.err.println("[EmailAutomationService] Draft creation failed for " + recipientEmail);
        }
        return draftId;
    }

    /**
     * Creates a Gmail Draft for the follow-up email.
     *
     * @return the Gmail Draft ID, or {@code null} if the draft could not be created
     */
    @Override
    public String sendFollowUp(
            @NonNull String recipientEmail,
            @NonNull String originalSubject,
            int daysSinceSent) {

        if (recipientEmail.isBlank()) throw new IllegalArgumentException("recipientEmail must not be blank");
        if (originalSubject.isBlank()) throw new IllegalArgumentException("originalSubject must not be blank");

        String draftId = gmailService.createFollowUpDraft(recipientEmail, originalSubject, daysSinceSent);
        if (draftId != null) {
            System.out.println("[EmailAutomationService] Follow-up draft created for " + recipientEmail + " (draftId=" + draftId + ")");
        } else {
            System.err.println("[EmailAutomationService] Follow-up draft creation failed for " + recipientEmail);
        }
        return draftId;
    }
}
