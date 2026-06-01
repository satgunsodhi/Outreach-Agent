package com.outreach.agent.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;

@Repository
public interface OutreachTargetRepository extends JpaRepository<OutreachTarget, Long> {
    List<OutreachTarget> findByStatusOrderByIdAsc(TargetStatus status);
    List<OutreachTarget> findByStatusAndFollowUpScheduledAtBefore(TargetStatus status, LocalDateTime time);
    List<OutreachTarget> findByStatusAndEmailScheduledAtBefore(TargetStatus status, LocalDateTime time);
    boolean existsByCompanyNameAndRecipientEmail(String companyName, String recipientEmail);
    List<OutreachTarget> findByClaimTokenOrderByIdAsc(String claimToken);

    @Modifying
    @Transactional
    @Query("UPDATE OutreachTarget t SET t.claimToken = :token, t.status = com.outreach.agent.model.TargetStatus.PROCESSING, t.processingStartedAt = :now WHERE t.status = com.outreach.agent.model.TargetStatus.PENDING AND t.claimToken IS NULL")
    int claimPendingTargets(@Param("token") String token, @Param("now") LocalDateTime now);

    /**
     * Returns targets that have been stuck in {@code status} since before {@code threshold}.
     * Used for stall recovery — only reverts targets that have been processing for too long,
     * not every target in the given status (which would be unsafe on startup).
     */
    @Query("SELECT t FROM OutreachTarget t WHERE t.status = :status AND t.processingStartedAt < :threshold")
    List<OutreachTarget> findStalledTargets(@Param("status") TargetStatus status,
                                            @Param("threshold") LocalDateTime threshold);

    /**
     * Returns a status-count aggregation for a single campaign, avoiding a full table scan.
     * Each element in the result list is {@code Object[]{TargetStatus, Long}}.
     */
    @Query("SELECT t.status, COUNT(t) FROM OutreachTarget t WHERE t.campaign.id = :campaignId GROUP BY t.status")
    List<Object[]> countByStatusForCampaign(@Param("campaignId") Long campaignId);
}
