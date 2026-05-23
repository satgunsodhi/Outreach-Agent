package com.outreach.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.service.ProjectDeepContextService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProjectDeepContextTool {

    private final ProjectDeepContextService deepContextService;
    private final ObjectMapper objectMapper;

    public ProjectDeepContextTool(ProjectDeepContextService deepContextService, ObjectMapper objectMapper) {
        this.deepContextService = deepContextService;
        this.objectMapper = objectMapper;
    }

    @Tool("Get deep technical context for selected projects including architecture details, key technologies, and suggested resume bullets. Pass a list of project IDs (e.g. proj-dasai, proj-slm) to get detailed context for crafting JD-tailored bullet points.")
    public String getDeepContext(List<String> projectIds) {
        try {
            Map<String, Map<String, Object>> context = deepContextService.getContextForProjects(projectIds);
            if (context.isEmpty()) {
                return "{\"message\": \"No deep context found for the given project IDs. Available IDs can be found in searchExperiences results.\"}";
            }
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            return "{\"error\": \"Failed to retrieve deep context: " + e.getMessage() + "\"}";
        }
    }
}
