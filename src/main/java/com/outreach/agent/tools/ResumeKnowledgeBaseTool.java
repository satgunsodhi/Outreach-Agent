package com.outreach.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.service.MasterResumeService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeKnowledgeBaseTool {

    private final MasterResumeService masterResumeService;
    private final ObjectMapper objectMapper;

    public ResumeKnowledgeBaseTool(MasterResumeService masterResumeService, ObjectMapper objectMapper) {
        this.masterResumeService = masterResumeService;
        this.objectMapper = objectMapper;
    }

    @Tool("Search the master resume knowledge base for experiences, projects, and bullet points matching given skill keywords")
    public String searchExperiences(List<String> keywords) {
        try {
            var results = masterResumeService.filterByTags(keywords);
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "{\"error\": \"Failed to search knowledge base: " + e.getMessage() + "\"}";
        }
    }

    @Tool("Reorder the items within each skill category so the skills most relevant to the provided JD keywords appear first. Also reorders skill categories themselves so the most relevant category appears first. Returns the reordered skills list.")
    public String reorderSkills(List<String> keywords) {
        try {
            var skills = masterResumeService.getMasterResume().skills();
            if (keywords == null || keywords.isEmpty()) {
                return objectMapper.writeValueAsString(skills);
            }
            List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).toList();

            // Step 1: sort items within each category by keyword match
            var reordered = skills.stream().map(category -> {
                List<String> sortedItems = new java.util.ArrayList<>(category.items());
                sortedItems.sort((a, b) -> {
                    boolean aMatch = lowerKeywords.stream().anyMatch(a.toLowerCase()::contains);
                    boolean bMatch = lowerKeywords.stream().anyMatch(b.toLowerCase()::contains);
                    if (aMatch && !bMatch) return -1;
                    if (!aMatch && bMatch) return 1;
                    return 0; // maintain original relative order
                });
                return new com.outreach.agent.model.SkillCategory(category.category(), sortedItems);
            }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

            // Step 2: sort categories themselves by how many of their items match JD keywords
            reordered.sort((catA, catB) -> {
                long aMatches = catA.items().stream()
                        .filter(item -> lowerKeywords.stream().anyMatch(item.toLowerCase()::contains))
                        .count();
                long bMatches = catB.items().stream()
                        .filter(item -> lowerKeywords.stream().anyMatch(item.toLowerCase()::contains))
                        .count();
                return Long.compare(bMatches, aMatches); // descending
            });

            return objectMapper.writeValueAsString(reordered);
        } catch (Exception e) {
            return "{\"error\": \"Failed to reorder skills: " + e.getMessage() + "\"}";
        }
    }
}
