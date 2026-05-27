package com.outreach.agent.service.impl;

import com.outreach.agent.service.EmailAutomationService;
import com.outreach.agent.service.GmailService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EmailAutomationServiceImpl.class);

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
            log.debug("Draft created for {} (draftId={})", recipientEmail, draftId);
            log.info("Draft created successfully (draftId={})", draftId);
        } else {
            log.debug("Draft creation failed for {}", recipientEmail);
            log.error("Draft creation failed");
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
            int daysSinceSent,
            String companyName,
            String roleName) {

        if (recipientEmail.isBlank()) throw new IllegalArgumentException("recipientEmail must not be blank");
        if (originalSubject.isBlank()) throw new IllegalArgumentException("originalSubject must not be blank");

        String draftId = gmailService.createFollowUpDraft(recipientEmail, originalSubject, daysSinceSent,
                companyName, roleName);
        if (draftId != null) {
            log.debug("Follow-up draft created for {} (draftId={})", recipientEmail, draftId);
            log.info("Follow-up draft created successfully (draftId={})", draftId);
        } else {
            log.debug("Follow-up draft creation failed for {}", recipientEmail);
            log.error("Follow-up draft creation failed");
        }
        return draftId;
    }

}
