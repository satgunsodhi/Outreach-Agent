package com.outreach.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class ProjectDeepContextService {

    private final ObjectMapper objectMapper;
    private Map<String, Map<String, Object>> deepContextMap;

    public ProjectDeepContextService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("data/project_deep_context.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.deepContextMap = objectMapper.readValue(inputStream,
                    new TypeReference<Map<String, Map<String, Object>>>() {
                    });
        }
        System.out.println("Loaded deep context for " + deepContextMap.size() + " projects.");
    }

    /**
     * Returns detailed deep context for the requested project IDs.
     * Unknown IDs are silently skipped.
     */
    public Map<String, Map<String, Object>> getContextForProjects(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return deepContextMap;
        }

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String id : projectIds) {
            Map<String, Object> ctx = deepContextMap.get(id);
            if (ctx != null) {
                result.put(id, ctx);
            }
        }
        return result;
    }

    /**
     * Returns a compact summary of all projects for the agent to use
     * during initial project selection (before fetching full deep context).
     */
    public List<Map<String, Object>> getAllProjectSummaries() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : deepContextMap.entrySet()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", entry.getKey());
            summary.put("coreProblem", entry.getValue().get("coreProblem"));
            summary.put("keyTechnologies", entry.getValue().get("keyTechnologies"));
            summaries.add(summary);
        }
        return summaries;
    }
}
