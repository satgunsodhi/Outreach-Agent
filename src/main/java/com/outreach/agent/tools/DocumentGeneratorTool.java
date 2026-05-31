package com.outreach.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.service.PdfGeneratorService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class DocumentGeneratorTool {

    private final PdfGeneratorService pdfGeneratorService;
    private final ObjectMapper objectMapper;
    private final com.outreach.agent.service.MasterResumeService masterResumeService;

    public DocumentGeneratorTool(PdfGeneratorService pdfGeneratorService, ObjectMapper objectMapper, com.outreach.agent.service.MasterResumeService masterResumeService) {
        this.pdfGeneratorService = pdfGeneratorService;
        this.objectMapper = objectMapper;
        this.masterResumeService = masterResumeService;
    }

    @Tool("Generate a PDF resume from selected resume data. Returns the file path of the generated PDF.")
    @SuppressWarnings("unchecked")
    public String generateResume(ResumeDataWrapper wrapper) {
        try {
            Map<String, Object> templateData = null;
            if (wrapper.getAdditionalProperties().containsKey("selectedDataJson")) {
                Object val = wrapper.getAdditionalProperties().get("selectedDataJson");
                if (val instanceof String s) {
                    String sanitizedJson = s
                        .replace("\u2011", "-")  // Replace non-breaking hyphen with standard hyphen
                        .replace("\u00A0", " "); // Replace non-breaking space with standard space
                    templateData = objectMapper.readValue(sanitizedJson, new TypeReference<Map<String, Object>>() {});
                } else if (val instanceof Map) {
                    templateData = (Map<String, Object>) val;
                }
            } else {
                templateData = wrapper.asMap();
            }

            if (templateData == null || templateData.isEmpty()) {
                return "Error: No resume data provided.";
            }

            try {
                com.outreach.agent.model.MasterResume masterResume = masterResumeService.getMasterResume();
                java.util.Set<String> validProjectNames = masterResume.projects().stream()
                        .map(p -> p.name().toLowerCase().trim())
                        .collect(java.util.stream.Collectors.toSet());
                
                masterResume.experiences().forEach(exp -> {
                    if (exp.projects() != null) {
                        exp.projects().forEach(p -> validProjectNames.add(p.name().toLowerCase().trim()));
                    }
                });

                Object projectsObj = templateData.get("projects");
                if (projectsObj instanceof java.util.List<?> list) {
                    for (Object p : list) {
                        if (p instanceof java.util.Map<?,?> pMap) {
                            Object nameObj = pMap.get("name");
                            if (nameObj instanceof String name) {
                                if (!validProjectNames.contains(name.toLowerCase().trim())) {
                                    return "Error: Hallucinated project detected: '" + name + "'. You must only use projects from the provided master_resume.json. Do not invent new projects.";
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                // Ignore validation error if it fails
            }

            try {
                String debugJson = objectMapper.writeValueAsString(templateData);
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("C:/Users/Satgu/.gemini/antigravity-ide/scratch/last_selected_data.json"),
                    debugJson
                );
            } catch (Exception ex) {
                // Ignore write failure of debug file
            }
            
            byte[] pdfBytes = pdfGeneratorService.generatePdf(templateData);
            
            // Create persistent directory for PDFs
            Path pdfDir = Path.of("data/generated-pdfs");
            if (!Files.exists(pdfDir)) {
                Files.createDirectories(pdfDir);
            }
            
            Path tempFile = Files.createTempFile(pdfDir, "resume-", ".pdf");
            Files.write(tempFile, pdfBytes);
            
            return tempFile.toAbsolutePath().toString().replace("\\", "/");
        } catch (Exception e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    public static class ResumeDataWrapper {
        private Map<String, Object> personalInfo;
        private String summary;
        private Object skills;
        private Object experiences;
        private Object projects;
        private Object education;
        private Object certifications;
        private Object extracurriculars;

        private Map<String, Object> additionalProperties = new java.util.HashMap<>();

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void set(String key, Object value) {
            additionalProperties.put(key, value);
        }

        public Map<String, Object> getPersonalInfo() { return personalInfo; }
        public void setPersonalInfo(Map<String, Object> personalInfo) { this.personalInfo = personalInfo; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public Object getSkills() { return skills; }
        public void setSkills(Object skills) { this.skills = skills; }

        public Object getExperiences() { return experiences; }
        public void setExperiences(Object experiences) { this.experiences = experiences; }

        public Object getProjects() { return projects; }
        public void setProjects(Object projects) { this.projects = projects; }

        public Object getEducation() { return education; }
        public void setEducation(Object education) { this.education = education; }

        public Object getCertifications() { return certifications; }
        public void setCertifications(Object certifications) { this.certifications = certifications; }

        public Object getExtracurriculars() { return extracurriculars; }
        public void setExtracurriculars(Object extracurriculars) { this.extracurriculars = extracurriculars; }

        public Map<String, Object> getAdditionalProperties() { return additionalProperties; }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new java.util.HashMap<>(additionalProperties);
            if (personalInfo != null) map.put("personalInfo", personalInfo);
            if (summary != null) map.put("summary", summary);
            if (skills != null) map.put("skills", skills);
            if (experiences != null) map.put("experiences", experiences);
            if (projects != null) map.put("projects", projects);
            if (education != null) map.put("education", education);
            if (certifications != null) map.put("certifications", certifications);
            if (extracurriculars != null) map.put("extracurriculars", extracurriculars);
            return map;
        }
    }
}
