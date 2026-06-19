package com.outreach.agent.service;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.Message;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Properties;

/**
 * Creates Gmail Drafts via the Gmail API (OAuth 2.0).
 * <p>
 * Replaces the JavaMailSender / SMTP approach entirely. All outreach emails and
 * follow-ups are placed in the authenticated user's Gmail Drafts folder so they
 * can be reviewed before sending manually.
 * </p>
 */
@Service
public class GmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailService.class);

    private static final String APPLICATION_NAME = "Outreach Agent";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final GoogleOAuthService oauthService;

    @Value("${gmail.from-address:}")
    private String fromAddress;

    private Gmail gmailClient;

    public GmailService(GoogleOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @PostConstruct
    public void init() {
        if (!oauthService.isAvailable()) {
            log.warn("OAuth not available. Draft creation will be disabled.");
            gmailClient = null;
            return;
        }

        gmailClient = new Gmail.Builder(
                oauthService.getHttpTransport(),
                JSON_FACTORY,
                oauthService.getCredential())
                .setApplicationName(APPLICATION_NAME)
                .build();

        log.info("Gmail service initialized successfully.");
    }

    /**
     * Creates a Gmail Draft addressed to {@code to} and returns the Gmail Draft ID.
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    plain-text email body
     * @return the Gmail Draft ID (e.g. {@code "r123456789"}), or {@code null} on failure
     */
    public String createDraft(String to, String subject, String body) {
        if (gmailClient == null) {
            log.warn("Gmail client not initialized - draft not created.");
            return null;
        }

        try {
            MimeMessage mimeMessage = buildMimeMessage(to, subject, body);
            Draft draft = new Draft().setMessage(toGmailMessage(mimeMessage));
            Draft created = gmailClient.users().drafts().create("me", draft).execute();
            log.debug("Draft created: id={}, to={}", created.getId(), to);
            log.info("Draft created: id={}", created.getId());
            return created.getId();
        } catch (Exception e) {
            log.debug("Failed to create draft for {}: {}", to, e.getMessage(), e);
            log.error("Failed to create draft: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a follow-up Gmail Draft (prepends "Re: " to the original subject).
     *
     * @param to              recipient email address
     * @param originalSubject the subject of the original outreach email
     * @param daysSinceSent   days elapsed since the original email — used to personalise the body
     * @return the Gmail Draft ID, or {@code null} on failure
     */
    public String createFollowUpDraft(String to, String originalSubject, int daysSinceSent,
                                       String companyName, String roleName, String candidateName) {
        String companyRef = (companyName != null && !companyName.isBlank()) ? companyName : "your team";
        String roleRef = (roleName != null && !roleName.isBlank()) ? " regarding the " + roleName + " role" : "";
        String signOff = (candidateName != null && !candidateName.isBlank())
                ? candidateName.contains(" ") ? candidateName.substring(0, candidateName.indexOf(' ')) : candidateName
                : "";

        String followUpBody = "Hi,\n\n"
                + "Just circling back on my earlier note" + roleRef + " at " + companyRef + ". "
                + "I know inboxes get busy, so wanted to bump this up in case it got buried.\n\n"
                + "Happy to jump on a quick call or answer any questions. Either way, no pressure.\n\n"
                + "Best,\n" + signOff;

        return createDraft(to, "Re: " + originalSubject, followUpBody);
    }


    // ── private helpers ───────────────────────────────────────────────────────

    private MimeMessage buildMimeMessage(String to, String subject, String body)
            throws MessagingException {
        Properties props = new Properties();
        Session session = Session.getInstance(props, null); // Fix #C: getDefaultInstance is deprecated and JVM-shared

        MimeMessage email = new MimeMessage(session);
        if (fromAddress != null && !fromAddress.isBlank()) {
            email.setFrom(new InternetAddress(fromAddress));
        }
        email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        email.setSubject(subject);
        // Convert newlines to HTML breaks to prevent rigid plain-text line-wrapping issues in email clients
        String htmlBody = body.replace("\r\n", "<br>").replace("\n", "<br>");
        email.setContent(htmlBody, "text/html; charset=utf-8");
        return email;
    }

    private Message toGmailMessage(MimeMessage mimeMessage) throws MessagingException, IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encodedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        return new Message().setRaw(encodedEmail);
    }

    /**
     * Deletes a Gmail Draft by its ID.
     *
     * @param draftId the Gmail Draft ID
     */
    public void deleteDraft(String draftId) {
        if (gmailClient == null || draftId == null || draftId.isBlank()) {
            return;
        }
        try {
            gmailClient.users().drafts().delete("me", draftId).execute();
            log.info("Deleted Gmail draft: {}", draftId);
        } catch (Exception e) {
            log.warn("Failed to delete Gmail draft {}: {}", draftId, e.getMessage());
        }
    }

    public enum ThreadStatus {
        NO_SENT_EMAIL,
        REPLIED,
        NO_REPLY
    }

    /**
     * Checks if a Gmail Draft is still pending (i.e. has not been sent or deleted).
     *
     * @param draftId the Gmail Draft ID
     * @return {@code true} if the draft still exists in Gmail, {@code false} otherwise
     */
    public boolean isDraftStillPending(String draftId) {
        if (gmailClient == null || draftId == null || draftId.isBlank()) {
            return false;
        }
        try {
            gmailClient.users().drafts().get("me", draftId).execute();
            return true;
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                return false; // Draft no longer exists
            }
            log.warn("Error checking draft status for {}: {}", draftId, e.getMessage());
        } catch (Exception e) {
            log.warn("Error checking draft status for {}: {}", draftId, e.getMessage());
        }
        return false;
    }

    /**
     * Checks if we sent an email and if the recipient replied.
     *
     * @param recipientEmail the email address of the recipient
     * @param subject        the original subject line
     * @return a {@link ThreadStatus} indicating the status of the thread
     */
    public ThreadStatus checkThreadStatus(String recipientEmail, String subject) {
        if (gmailClient == null || recipientEmail == null || recipientEmail.isBlank() || subject == null || subject.isBlank()) {
            return ThreadStatus.NO_SENT_EMAIL;
        }
        try {
            String cleanSubject = subject.replaceAll("^(?i)Re:\\s*", "");
            // Escape any embedded double-quotes so the Gmail query syntax is not broken
            String safeSubject = cleanSubject.replace("\"", "\\\"");
            String query = "to:" + recipientEmail + " subject:\"" + safeSubject + "\"";
            var response = gmailClient.users().threads().list("me").setQ(query).execute();
            java.util.List<com.google.api.services.gmail.model.Thread> threads = response.getThreads();
            if (threads == null || threads.isEmpty()) {
                return ThreadStatus.NO_SENT_EMAIL;
            }

            // Fix #6: iterate all matching threads to find one where we are the sender,
            // instead of blindly using threads.get(0) which may be an inbound thread.
            for (com.google.api.services.gmail.model.Thread rawThread : threads) {
                com.google.api.services.gmail.model.Thread thread =
                        gmailClient.users().threads().get("me", rawThread.getId()).execute();
                java.util.List<Message> messages = thread.getMessages();
                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                boolean sentByMe = false;
                boolean receivedReply = false;
                for (Message msg : messages) {
                    if (msg.getPayload() == null || msg.getPayload().getHeaders() == null) {
                        continue;
                    }
                    java.util.List<com.google.api.services.gmail.model.MessagePartHeader> headers = msg.getPayload().getHeaders();
                    String from = "";
                    for (var header : headers) {
                        if ("From".equalsIgnoreCase(header.getName())) {
                            from = header.getValue();
                            break;
                        }
                    }

                    boolean isFromMe = fromAddress != null && !fromAddress.isBlank() && from.toLowerCase().contains(fromAddress.toLowerCase());
                    if (isFromMe) {
                        sentByMe = true;
                    } else {
                        // B7: A real reply must have arrived in our INBOX (not a bounce, CC’d
                        // auto-responder, or forwarded copy). Check for the INBOX label.
                        boolean hasInboxLabel = msg.getLabelIds() != null
                                && msg.getLabelIds().contains("INBOX");
                        if (hasInboxLabel) {
                            receivedReply = true;
                        }
                    }
                }

                if (sentByMe) {
                    return receivedReply ? ThreadStatus.REPLIED : ThreadStatus.NO_REPLY;
                }
                // sentByMe == false for this thread: it's an inbound-only thread, keep searching
            }

            return ThreadStatus.NO_SENT_EMAIL;
        } catch (Exception e) {
            log.warn("Failed to check thread status for {}: {}", recipientEmail, e.getMessage());
            return ThreadStatus.NO_SENT_EMAIL;
        }
    }
}
