package com.outreach.agent.model;

import java.util.List;

public record Project(
    String id,
    String name,
    String github,
    String liveDemo,
    String techStack,
    List<String> tags,
    List<BulletPoint> bullets
) {}
