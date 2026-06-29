package com.outreach.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

@Service
public class MasterResumeService {

    private final ObjectMapper objectMapper;
    private MasterResume masterResume;
    /** Pre-serialized JSON of the master resume — computed once at startup to avoid per-request serialization overhead. */
    private String masterResumeJson;

    public MasterResumeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("data/master_resume.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.masterResume = objectMapper.readValue(inputStream, MasterResume.class);
        }
        this.masterResumeJson = objectMapper.writeValueAsString(this.masterResume);
    }

    public MasterResume getMasterResume() {
        return masterResume;
    }

    /** Returns the pre-serialized JSON string of the master resume. Avoids repeated serialization overhead in batch loops. */
    public String getMasterResumeJson() {
        return masterResumeJson;
    }

    public MasterResume filterByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return masterResume;
        }

        List<String> lowerTags = tags.stream().map(t -> t.toLowerCase()).toList();

        List<Experience> filteredExperiences = masterResume.experiences().stream()
                .map(exp -> {
                    List<Project> filteredProjects = exp.projects().stream()
                            .map(proj -> {
                                List<BulletPoint> filteredBullets = proj.bullets().stream()
                                        .filter(bp -> matchesAny(bp.tags(), lowerTags))
                                        .sorted(Comparator.comparingInt((BulletPoint bp) -> countMatches(bp.tags(), lowerTags)).reversed())
                                        .toList();
                                List<BulletPoint> finalBullets = filteredBullets.isEmpty() ? proj.bullets() : filteredBullets;
                                return new Project(proj.id(), proj.name(), proj.github(), proj.liveDemo(), proj.techStack(), proj.tags(), finalBullets);
                            })
                            .toList();
                    return new Experience(exp.id(), exp.company(), exp.title(), exp.startDate(), exp.endDate(), exp.location(), exp.tags(), filteredProjects);
                })
                .toList();

        List<Project> filteredProjects = masterResume.projects().stream()
                .map(proj -> {
                    List<BulletPoint> filteredBullets = proj.bullets().stream()
                            .filter(bp -> matchesAny(bp.tags(), lowerTags))
                            .sorted(Comparator.comparingInt((BulletPoint bp) -> countMatches(bp.tags(), lowerTags)).reversed())
                            .toList();
                    List<BulletPoint> finalBullets = filteredBullets.isEmpty() ? proj.bullets() : filteredBullets;
                    return new Project(proj.id(), proj.name(), proj.github(), proj.liveDemo(), proj.techStack(), proj.tags(), finalBullets);
                })
                .filter(proj -> !proj.bullets().isEmpty() || matchesAny(proj.tags(), lowerTags))
                .sorted(Comparator.comparingInt((Project proj) -> {
                    int projectMatches = countMatches(proj.tags(), lowerTags);
                    int bulletMatches = proj.bullets().stream().mapToInt(bp -> countMatches(bp.tags(), lowerTags)).sum();
                    return projectMatches + bulletMatches;
                }).reversed())
                .toList();

        List<Extracurricular> filteredExtracurriculars = masterResume.extracurriculars() != null 
                ? masterResume.extracurriculars().stream()
                        .map(ec -> {
                            List<BulletPoint> filteredBullets = ec.bullets().stream()
                                    .filter(bp -> matchesAny(bp.tags(), lowerTags))
                                    .sorted(Comparator.comparingInt((BulletPoint bp) -> countMatches(bp.tags(), lowerTags)).reversed())
                                    .toList();
                            List<BulletPoint> finalBullets = filteredBullets.isEmpty() ? ec.bullets() : filteredBullets;
                            return new Extracurricular(ec.id(), ec.organization(), ec.role(), ec.tags(), finalBullets);
                        })
                        .toList()
                : List.of();

        // Fallback: If both experiences and projects end up completely empty after filtering,
        // return the original unfiltered lists so the agent has material to build a resume.
        List<Experience> finalExperiences = filteredExperiences.isEmpty() && filteredProjects.isEmpty() ? masterResume.experiences() : filteredExperiences;
        List<Project> finalProjects = filteredExperiences.isEmpty() && filteredProjects.isEmpty() ? masterResume.projects() : filteredProjects;
        List<Extracurricular> finalExtracurriculars = filteredExperiences.isEmpty() && filteredProjects.isEmpty() ? masterResume.extracurriculars() : filteredExtracurriculars;

        return new MasterResume(
                masterResume.personalInfo(),
                masterResume.skills(),
                finalExperiences,
                finalProjects,
                masterResume.education(),
                masterResume.certifications(),
                finalExtracurriculars
        );
    }

    private boolean matchesAny(List<String> itemTags, List<String> searchTags) {
        if (itemTags == null || itemTags.isEmpty()) return false;
        return itemTags.stream()
                .map(t -> t.toLowerCase())
                .anyMatch(searchTags::contains);
    }

    private int countMatches(List<String> itemTags, List<String> searchTags) {
        if (itemTags == null || itemTags.isEmpty()) return 0;
        return (int) itemTags.stream()
                .map(t -> t.toLowerCase())
                .filter(searchTags::contains)
                .count();
    }
}
