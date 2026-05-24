package com.outreach.agent.model;

import java.util.List;

public record MasterResume(
    PersonalInfo personalInfo,
    List<SkillCategory> skills,
    List<Experience> experiences,
    List<Project> projects,
    List<Education> education,
    List<Certification> certifications,
    List<Extracurricular> extracurriculars
) {}
