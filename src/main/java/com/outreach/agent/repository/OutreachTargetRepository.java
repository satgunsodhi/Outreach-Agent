package com.outreach.agent.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.outreach.agent.model.OutreachTarget;

@Repository
public interface OutreachTargetRepository extends JpaRepository<OutreachTarget, Long> {
    List<OutreachTarget> findByStatusOrderByIdAsc(String status);
    List<OutreachTarget> findByStatusAndFollowUpScheduledAtBefore(String status, LocalDateTime time);
    List<OutreachTarget> findByStatusAndEmailScheduledAtBefore(String status, LocalDateTime time);
    boolean existsByCompanyNameAndRecipientEmail(String companyName, String recipientEmail);
}
