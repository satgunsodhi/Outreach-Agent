package com.outreach.agent.model;

/**
 * Lifecycle states for an {@link OutreachCampaign}.
 * Replaces the previous untyped {@code String} status field.
 */
public enum CampaignStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}
