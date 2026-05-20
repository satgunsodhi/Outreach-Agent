package com.outreach.agent.model;

import java.util.List;

public record SkillCategory(
    String category,
    List<String> items
) {}
