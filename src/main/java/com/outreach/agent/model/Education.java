package com.outreach.agent.model;

import java.util.List;

public record Education(
    String degree,
    String institution,
    String graduationDate,
    String gpa,
    List<String> tags
) {}
