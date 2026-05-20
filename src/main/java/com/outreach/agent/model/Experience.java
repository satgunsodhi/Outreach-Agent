package com.outreach.agent.model;

import java.util.List;

public record Experience(
    String id,
    String company,
    String title,
    String startDate,
    String endDate,
    List<String> tags,
    List<Project> projects
) {}
