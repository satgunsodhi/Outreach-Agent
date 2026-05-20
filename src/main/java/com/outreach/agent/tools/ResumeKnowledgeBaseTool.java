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
}
