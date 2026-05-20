package com.outreach.agent.service.impl;

import com.outreach.agent.service.EmailAutomationService;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailAutomationServiceImpl implements EmailAutomationService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    public EmailAutomationServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendResumeEmail(String recipientEmail, String subject, byte[] resumePdf, String coverLetterBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setFrom(fromEmail);
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
    public void sendFollowUp(String recipientEmail, String originalSubject, int daysSinceSent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);

            helper.setFrom(fromEmail);
            helper.setTo(recipientEmail);
            helper.setSubject("Re: " + originalSubject);
            
            String followUpBody = "Hi,\n\nI wanted to follow up on my previous email sent " + daysSinceSent + " days ago.\n\nBest regards.";
            helper.setText(followUpBody, false);

            mailSender.send(message);
            System.out.println("Follow-up email sent successfully to " + recipientEmail);
        } catch (Exception e) {
            System.err.println("Failed to send follow-up email to " + recipientEmail + ": " + e.getMessage());
        }
    }
}
