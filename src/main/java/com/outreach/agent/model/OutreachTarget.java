package com.outreach.agent.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "outreach_targets")
public class OutreachTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private OutreachCampaign campaign;

    private String companyName;
    private String recipientEmail;
    
    @Column(length = 2000)
    private String jobUrl;
    
    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    private String status; // PENDING, GENERATING, EMAIL_SENT, FOLLOW_UP_SCHEDULED, FOLLOW_UP_SENT, FAILED
    
    @Column(columnDefinition = "TEXT")
    private String errorReason;

    private LocalDateTime createdAt;
    private LocalDateTime emailSentAt;
    private LocalDateTime followUpScheduledAt;
    
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String draftedCoverLetter;

    private String generatedPdfPath;

    /** Gmail Draft ID returned after creating the draft via the Gmail API. */
    private String gmailDraftId;

    private LocalDateTime emailScheduledAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OutreachCampaign getCampaign() {
        return campaign;
    }

    public void setCampaign(OutreachCampaign campaign) {
        this.campaign = campaign;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public String getJobUrl() {
        return jobUrl;
    }

    public void setJobUrl(String jobUrl) {
        this.jobUrl = jobUrl;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getEmailSentAt() {
        return emailSentAt;
    }

    public void setEmailSentAt(LocalDateTime emailSentAt) {
        this.emailSentAt = emailSentAt;
    }

    public LocalDateTime getFollowUpScheduledAt() {
        return followUpScheduledAt;
    }

    public void setFollowUpScheduledAt(LocalDateTime followUpScheduledAt) {
        this.followUpScheduledAt = followUpScheduledAt;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDraftedCoverLetter() {
        return draftedCoverLetter;
    }

    public void setDraftedCoverLetter(String draftedCoverLetter) {
        this.draftedCoverLetter = draftedCoverLetter;
    }

    public String getGeneratedPdfPath() {
        return generatedPdfPath;
    }

    public void setGeneratedPdfPath(String generatedPdfPath) {
        this.generatedPdfPath = generatedPdfPath;
    }

    public String getGmailDraftId() {
        return gmailDraftId;
    }

    public void setGmailDraftId(String gmailDraftId) {
        this.gmailDraftId = gmailDraftId;
    }

    public LocalDateTime getEmailScheduledAt() {
        return emailScheduledAt;
    }

    public void setEmailScheduledAt(LocalDateTime emailScheduledAt) {
        this.emailScheduledAt = emailScheduledAt;
    }
}
