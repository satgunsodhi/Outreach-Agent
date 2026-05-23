package com.outreach.agent.dto;

import java.util.List;

public class BatchOutreachRequest {
    
    private String campaignName;
    private List<TargetDto> targets;

    public String getCampaignName() {
        return campaignName;
    }

    public void setCampaignName(String campaignName) {
        this.campaignName = campaignName;
    }

    public List<TargetDto> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetDto> targets) {
        this.targets = targets;
    }
}
