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

    public MasterResumeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("data/master_resume.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.masterResume = objectMapper.readValue(inputStream, MasterResume.class);
        }
    }

    public MasterResume getMasterResume() {
        return masterResume;
    }

    public MasterResume filterByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return masterResume;
        }

        List<String> lowerTags = tags.stream().map(String::toLowerCase).toList();

        List<Experience> filteredExperiences = masterResume.experiences().stream()
                .map(exp -> {
                    List<Project> filteredProjects = exp.projects().stream()
                            .map(proj -> {
                                List<BulletPoint> filteredBullets = proj.bullets().stream()
                                        .filter(bp -> matchesAny(bp.tags(), lowerTags))
                                        .sorted(Comparator.comparingInt((BulletPoint bp) -> countMatches(bp.tags(), lowerTags)).reversed())
                                        .toList();
                                return new Project(proj.id(), proj.name(), proj.tags(), filteredBullets);
                            })
                            .filter(proj -> !proj.bullets().isEmpty() || matchesAny(proj.tags(), lowerTags))
                            .sorted(Comparator.comparingInt((Project proj) -> countMatches(proj.tags(), lowerTags)).reversed())
                            .toList();
                    return new Experience(exp.id(), exp.company(), exp.title(), exp.startDate(), exp.endDate(), exp.tags(), filteredProjects);
                })
                .filter(exp -> !exp.projects().isEmpty() || matchesAny(exp.tags(), lowerTags))
                .sorted(Comparator.comparingInt((Experience exp) -> countMatches(exp.tags(), lowerTags)).reversed())
                .toList();

        List<Project> filteredProjects = masterResume.projects().stream()
                .map(proj -> {
                    List<BulletPoint> filteredBullets = proj.bullets().stream()
                            .filter(bp -> matchesAny(bp.tags(), lowerTags))
                            .sorted(Comparator.comparingInt((BulletPoint bp) -> countMatches(bp.tags(), lowerTags)).reversed())
                            .toList();
                    return new Project(proj.id(), proj.name(), proj.tags(), filteredBullets);
                })
                .filter(proj -> !proj.bullets().isEmpty() || matchesAny(proj.tags(), lowerTags))
                .sorted(Comparator.comparingInt((Project proj) -> countMatches(proj.tags(), lowerTags)).reversed())
                .toList();

        // Fallback: If both experiences and projects end up completely empty after filtering,
        // return the original unfiltered lists so the agent has material to build a resume.
        List<Experience> finalExperiences = filteredExperiences.isEmpty() && filteredProjects.isEmpty() ? masterResume.experiences() : filteredExperiences;
        List<Project> finalProjects = filteredExperiences.isEmpty() && filteredProjects.isEmpty() ? masterResume.projects() : filteredProjects;

        return new MasterResume(
                masterResume.personalInfo(),
                masterResume.skills(),
                finalExperiences,
                finalProjects,
                masterResume.education(),
                masterResume.certifications()
        );
    }

    private boolean matchesAny(List<String> itemTags, List<String> searchTags) {
        if (itemTags == null || itemTags.isEmpty()) return false;
        return itemTags.stream()
                .map(String::toLowerCase)
                .anyMatch(searchTags::contains);
    }

    private int countMatches(List<String> itemTags, List<String> searchTags) {
        if (itemTags == null || itemTags.isEmpty()) return 0;
        return (int) itemTags.stream()
                .map(String::toLowerCase)
                .filter(searchTags::contains)
                .count();
    }
}
