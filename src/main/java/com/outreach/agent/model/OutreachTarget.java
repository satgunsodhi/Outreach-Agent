package com.outreach.agent.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA entity representing a single outreach target — one company/recipient pair
 * moving through the drafting pipeline.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "outreach_targets", indexes = {
        @jakarta.persistence.Index(name = "idx_target_status", columnList = "status"),
        @jakarta.persistence.Index(name = "idx_target_claim_token", columnList = "claimToken")
})
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

    @Enumerated(EnumType.STRING)
    private TargetStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorReason;

    private LocalDateTime createdAt;
    private LocalDateTime emailSentAt;
    /** Timestamp when the Gmail draft was created. Distinct from emailSentAt, which records the actual send time. */
    private LocalDateTime draftCreatedAt;
    private LocalDateTime followUpScheduledAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime processingCompletedAt;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String draftedCoverLetter;

    private String generatedPdfPath;

    /** Gmail Draft ID returned after creating the draft via the Gmail API. */
    private String gmailDraftId;

    private LocalDateTime emailScheduledAt;

    /** Timestamp after which this target can be retried. null means it can be processed immediately. */
    private LocalDateTime nextAttemptAt;

    /** Number of times this target has been retried after a transient failure. */
    @Column(columnDefinition = "integer default 0")
    private int retryCount = 0;

    /** UUID claim token to prevent multi-process concurrency races. */
    private String claimToken;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
