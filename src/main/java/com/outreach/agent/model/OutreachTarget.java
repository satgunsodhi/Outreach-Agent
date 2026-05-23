package com.outreach.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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
}
