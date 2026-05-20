package com.outreach.agent.model;

import java.util.List;

public record BulletPoint(
    String id,
    String text,
    List<String> tags,
    Integer priority,
    String lengthCategory
) {}
