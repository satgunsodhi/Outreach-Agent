package com.outreach.agent.dto;

/**
 * DTO for a single outreach target submitted via the batch API.
 */
public record TargetDto(
        String companyName,
        String recipientEmail,
        String jobUrl,
        String jobDescription
) {}
