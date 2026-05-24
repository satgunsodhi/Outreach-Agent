package com.outreach.agent.model;

import java.util.List;

public record Extracurricular(
    String id,
    String organization,
    String role,
    List<String> tags,
    List<BulletPoint> bullets
) {}
