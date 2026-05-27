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
            System.err.println("[GmailService] OAuth not available. Draft creation will be disabled.");
            gmailClient = null;
            return;
        }

        gmailClient = new Gmail.Builder(
                oauthService.getHttpTransport(),
                JSON_FACTORY,
                oauthService.getCredential())
                .setApplicationName(APPLICATION_NAME)
                .build();

        System.out.println("[GmailService] Initialized successfully.");
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
            System.err.println("[GmailService] Gmail client not initialized — draft not created.");
            return null;
        }

        try {
            MimeMessage mimeMessage = buildMimeMessage(to, subject, body);
            Draft draft = new Draft().setMessage(toGmailMessage(mimeMessage));
            Draft created = gmailClient.users().drafts().create("me", draft).execute();
            System.out.println("[GmailService] Draft created → id=" + created.getId() + "  to=" + to);
            return created.getId();
        } catch (Exception e) {
            System.err.println("[GmailService] Failed to create draft for " + to + ": " + e.getMessage());
            e.printStackTrace();
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
    public String createFollowUpDraft(String to, String originalSubject, int daysSinceSent) {
        String followUpBody = "Hi,\n\n"
                + "I wanted to follow up on my previous email sent " + daysSinceSent + " day"
                + (daysSinceSent == 1 ? "" : "s") + " ago regarding the opportunity at your company.\n\n"
                + "I remain very interested and would love the chance to connect.\n\n"
                + "Best regards,\nSatgun Singh Sodhi";

        return createDraft(to, "Re: " + originalSubject, followUpBody);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private MimeMessage buildMimeMessage(String to, String subject, String body)
            throws MessagingException {
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

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
}
