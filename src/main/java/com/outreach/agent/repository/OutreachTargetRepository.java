package com.outreach.agent.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.model.TargetStatus;

@Repository
public interface OutreachTargetRepository extends JpaRepository<OutreachTarget, Long> {
    List<OutreachTarget> findByStatusOrderByIdAsc(TargetStatus status);
    List<OutreachTarget> findByStatusAndFollowUpScheduledAtBefore(TargetStatus status, LocalDateTime time);
    List<OutreachTarget> findByStatusAndEmailScheduledAtBefore(TargetStatus status, LocalDateTime time);
    boolean existsByCompanyNameAndRecipientEmail(String companyName, String recipientEmail);
}
