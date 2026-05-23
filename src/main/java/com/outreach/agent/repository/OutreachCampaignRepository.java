package com.outreach.agent.repository;

import com.outreach.agent.model.OutreachCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutreachCampaignRepository extends JpaRepository<OutreachCampaign, Long> {
}
