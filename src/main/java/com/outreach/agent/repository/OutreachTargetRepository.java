package com.outreach.agent.repository;

import com.outreach.agent.model.OutreachTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutreachTargetRepository extends JpaRepository<OutreachTarget, Long> {
    List<OutreachTarget> findByStatus(String status);
    List<OutreachTarget> findByStatusAndFollowUpScheduledAtBefore(String status, LocalDateTime time);
}
