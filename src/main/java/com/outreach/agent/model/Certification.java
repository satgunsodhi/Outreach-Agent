package com.outreach.agent.model;

import java.util.List;

public record Certification(
    String name,
    String issuer,
    String date,
    List<String> tags
) {}
