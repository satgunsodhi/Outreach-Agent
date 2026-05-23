package com.outreach.agent.service.impl;

import com.outreach.agent.service.EmailAutomationService;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.lang.NonNull;
import java.util.Objects;

@Service
public class EmailAutomationServiceImpl implements EmailAutomationService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    public EmailAutomationServiceImpl(JavaMailSender mailSender) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender must not be null");
    }

    @Override
    public void sendResumeEmail(
            @NonNull String recipientEmail,
            @NonNull String subject,
            byte[] resumePdf,
            @NonNull String coverLetterBody) {
        if (recipientEmail == null || recipientEmail.isBlank())
            throw new IllegalArgumentException("recipientEmail must not be null or blank");
        if (subject == null || subject.isBlank())
            throw new IllegalArgumentException("subject must not be null or blank");
        if (resumePdf == null || resumePdf.length == 0)
            throw new IllegalArgumentException("resumePdf must not be null or empty");
        if (coverLetterBody == null || coverLetterBody.isBlank())
            throw new IllegalArgumentException("coverLetterBody must not be null or blank");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            String emailSenderAddress = fromEmail;
            String from = emailSenderAddress != null && !emailSenderAddress.isBlank() ? emailSenderAddress : "noreply@example.com";
            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(coverLetterBody, false);

            // Attach the PDF
            helper.addAttachment("Resume.pdf", new ByteArrayResource(resumePdf));

            mailSender.send(message);
            System.out.println("Email sent successfully to " + recipientEmail);
        } catch (Exception e) {
            System.err.println("Failed to send email to " + recipientEmail + ": " + e.getMessage());
        }
    }

    @Override
    public void sendFollowUp(
            @NonNull String recipientEmail,
            @NonNull String originalSubject,
            int daysSinceSent) {
        if (recipientEmail == null || recipientEmail.isBlank())
            throw new IllegalArgumentException("recipientEmail must not be null or blank");
        if (originalSubject == null || originalSubject.isBlank())
            throw new IllegalArgumentException("originalSubject must not be null or blank");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);

            String emailSenderAddress = fromEmail;
            String from = emailSenderAddress != null && !emailSenderAddress.isBlank() ? emailSenderAddress : "noreply@example.com";
            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject("Re: " + originalSubject);

            String followUpBody = "Hi,\n\nI wanted to follow up on my previous email sent " + daysSinceSent
                    + " days ago.\n\nBest regards.";
            helper.setText(followUpBody, false);

            mailSender.send(message);
            System.out.println("Follow-up email sent successfully to " + recipientEmail);
        } catch (Exception e) {
            System.err.println("Failed to send follow-up email to " + recipientEmail + ": " + e.getMessage());
        }
    }
}
