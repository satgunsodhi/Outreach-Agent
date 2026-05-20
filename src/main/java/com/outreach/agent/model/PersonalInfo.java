package com.outreach.agent.model;

public record PersonalInfo(
    String name,
    String email,
    String phone,
    String linkedin,
    String github,
    String location,
    String summary
) {}
