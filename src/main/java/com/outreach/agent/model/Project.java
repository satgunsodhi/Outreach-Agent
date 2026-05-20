package com.outreach.agent.model;

import java.util.List;

public record Project(
    String id,
    String name,
    List<String> tags,
    List<BulletPoint> bullets
) {}
