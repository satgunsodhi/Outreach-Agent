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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentGeneratorTool.class);

    private final PdfGeneratorService pdfGeneratorService;
    private final ObjectMapper objectMapper;
    private final com.outreach.agent.service.MasterResumeService masterResumeService;

    public DocumentGeneratorTool(PdfGeneratorService pdfGeneratorService, ObjectMapper objectMapper,
            com.outreach.agent.service.MasterResumeService masterResumeService) {
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
                            .replace("\u2011", "-") // Replace non-breaking hyphen with standard hyphen
                            .replace("\u00A0", " "); // Replace non-breaking space with standard space
                    templateData = objectMapper.readValue(sanitizedJson, new TypeReference<Map<String, Object>>() {
                    });
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
                        .filter(p -> p.name() != null)
                        .map(p -> p.name().toLowerCase().trim())
                        .collect(java.util.stream.Collectors.toSet());

                masterResume.experiences().forEach(exp -> {
                    if (exp.projects() != null) {
                        exp.projects().stream()
                                .filter(p -> p.name() != null)
                                .forEach(p -> validProjectNames.add(p.name().toLowerCase().trim()));
                    }
                });

                Object projectsObj = templateData.get("projects");
                if (projectsObj instanceof java.util.List<?> list) {
                    for (Object p : list) {
                        if (p instanceof java.util.Map<?, ?> pMap) {
                            Object nameObj = pMap.get("name");
                            if (nameObj instanceof String name) {
                                if (!validProjectNames.contains(name.toLowerCase().trim())) {
                                    return "Error: Hallucinated project detected: '" + name
                                            + "'. You must only use projects from the provided master_resume.json. Do not invent new projects.";
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                // A failure in the validation infrastructure itself is a real error — do not
                // silently proceed.
                log.error("Project hallucination validator threw unexpectedly: {}", ex.getMessage(), ex);
                return "Error: Could not validate project names. Aborting to prevent hallucinated data from being used.";
            }

            try {
                java.nio.file.Path debugDir = java.nio.file.Path.of("data/debug");
                java.nio.file.Files.createDirectories(debugDir);
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                String debugJson = objectMapper.writeValueAsString(templateData);
                log.info("RAW TEMPLATE DATA KEYS: {}", templateData.keySet());
                java.nio.file.Files.writeString(
                        debugDir.resolve("selected_data_raw_" + timestamp + ".json"),
                        debugJson);
            } catch (Exception ex) {
                log.warn("Failed to write raw debug file: {}", ex.getMessage());
            }

            enrichTemplateData(templateData);

            try {
                java.nio.file.Path debugDir = java.nio.file.Path.of("data/debug");
                java.nio.file.Files.createDirectories(debugDir);
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                String debugJson = objectMapper.writeValueAsString(templateData);
                java.nio.file.Files.writeString(
                        debugDir.resolve("selected_data_enriched_" + timestamp + ".json"),
                        debugJson);
            } catch (Exception ex) {
                log.warn("Failed to write enriched debug file: {}", ex.getMessage());
            }

            byte[] pdfBytes = pdfGeneratorService.generatePdf(templateData);
            int pageCount = pdfGeneratorService.countPages(pdfBytes);

            if (pageCount > 2) {
                log.info("Generated PDF has {} pages (exceeds 2-page budget). Starting programmatic optimization...",
                        pageCount);

                // Step 1: Remove extracurriculars to save some space
                if (pageCount > 2 && templateData.containsKey("extracurriculars")) {
                    templateData.remove("extracurriculars");
                    pdfBytes = pdfGeneratorService.generatePdf(templateData);
                    pageCount = pdfGeneratorService.countPages(pdfBytes);
                    log.info("After Step 1 (remove extracurriculars): {} pages", pageCount);
                }

                // Step 2: Cut bullets to max 2 per project (baseline is 2-3, so this is the first trim)
                if (pageCount > 2 && reduceBullets(templateData, 2)) {
                    pdfBytes = pdfGeneratorService.generatePdf(templateData);
                    pageCount = pdfGeneratorService.countPages(pdfBytes);
                    log.info("After Step 2 (max 2 bullets): {} pages", pageCount);
                }

                // Step 3: Trim skills list to max 12 per category
                if (pageCount > 2) {
                    if (trimSkills(templateData, 12)) {
                        pdfBytes = pdfGeneratorService.generatePdf(templateData);
                        pageCount = pdfGeneratorService.countPages(pdfBytes);
                        log.info("After Step 3 (trim skills): {} pages", pageCount);
                    }
                }

                // Step 4: Last resort — drop to 2 projects (should rarely fire given 3 is the baseline)
                if (pageCount > 2) {
                    if (reduceProjects(templateData, 2)) {
                        pdfBytes = pdfGeneratorService.generatePdf(templateData);
                        pageCount = pdfGeneratorService.countPages(pdfBytes);
                        log.info("After Step 4 (max 2 projects): {} pages", pageCount);
                    }
                }
            }

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

    @SuppressWarnings("unchecked")
    private boolean reduceBullets(Map<String, Object> templateData, int maxBullets) {
        boolean changed = false;

        // 1. Independent projects
        Object projectsObj = templateData.get("projects");
        if (projectsObj instanceof java.util.List<?> projects) {
            for (Object proj : projects) {
                if (proj instanceof Map<?, ?> projMap) {
                    Map<String, Object> pm = (Map<String, Object>) projMap;
                    Object bulletsObj = pm.get("bullets");
                    if (bulletsObj instanceof java.util.List<?> bullets && bullets.size() > maxBullets) {
                        pm.put("bullets", new java.util.ArrayList<>(bullets.subList(0, maxBullets)));
                        changed = true;
                    }
                }
            }
        }

        // 2. Experience projects
        Object experiencesObj = templateData.get("experiences");
        if (experiencesObj instanceof java.util.List<?> experiences) {
            for (Object exp : experiences) {
                if (exp instanceof Map<?, ?> expMap) {
                    Map<String, Object> em = (Map<String, Object>) expMap;
                    Object expProjectsObj = em.get("projects");
                    if (expProjectsObj instanceof java.util.List<?> expProjects) {
                        for (Object proj : expProjects) {
                            if (proj instanceof Map<?, ?> projMap) {
                                Map<String, Object> pm = (Map<String, Object>) projMap;
                                Object bulletsObj = pm.get("bullets");
                                if (bulletsObj instanceof java.util.List<?> bullets && bullets.size() > maxBullets) {
                                    pm.put("bullets", new java.util.ArrayList<>(bullets.subList(0, maxBullets)));
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        return changed;
    }

    private boolean reduceProjects(Map<String, Object> templateData, int maxProjects) {
        Object projectsObj = templateData.get("projects");
        if (projectsObj instanceof java.util.List<?> projects && projects.size() > maxProjects) {
            templateData.put("projects", new java.util.ArrayList<>(projects.subList(0, maxProjects)));
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean trimSkills(Map<String, Object> templateData, int maxSkillsPerCategory) {
        boolean changed = false;
        Object skillsObj = templateData.get("skills");
        if (skillsObj instanceof java.util.List<?> skills) {
            for (Object skill : skills) {
                if (skill instanceof Map<?, ?> skillMap) {
                    Map<String, Object> sm = (Map<String, Object>) skillMap;
                    Object itemsObj = sm.get("items");
                    if (itemsObj instanceof java.util.List<?> items && items.size() > maxSkillsPerCategory) {
                        sm.put("items", new java.util.ArrayList<>(items.subList(0, maxSkillsPerCategory)));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private void enrichTemplateData(Map<String, Object> templateData) {
        try {
            com.outreach.agent.model.MasterResume masterResume = masterResumeService.getMasterResume();

            // Create lookup maps
            java.util.Map<String, com.outreach.agent.model.Project> projectMap = new java.util.HashMap<>();
            if (masterResume.projects() != null) {
                for (com.outreach.agent.model.Project p : masterResume.projects()) {
                    if (p.id() != null) projectMap.put(p.id(), p);
                }
            }

            java.util.Map<String, com.outreach.agent.model.Experience> experienceMap = new java.util.HashMap<>();
            java.util.Map<String, com.outreach.agent.model.Project> expProjectMap = new java.util.HashMap<>();
            if (masterResume.experiences() != null) {
                for (com.outreach.agent.model.Experience e : masterResume.experiences()) {
                    if (e.id() != null) experienceMap.put(e.id(), e);
                    if (e.projects() != null) {
                        for (com.outreach.agent.model.Project p : e.projects()) {
                            if (p.id() != null) expProjectMap.put(p.id(), p);
                        }
                    }
                }
            }

            // Enrich independent projects
            Object projectsObj = templateData.get("projects");
            if (projectsObj instanceof java.util.List<?> projectsList) {
                for (Object proj : projectsList) {
                    if (proj instanceof Map<?, ?> projMap) {
                        Map<String, Object> pm = (Map<String, Object>) projMap;
                        Object idObj = pm.get("id");
                        if (idObj instanceof String id && projectMap.containsKey(id)) {
                            com.outreach.agent.model.Project masterProj = projectMap.get(id);
                            pm.putIfAbsent("name", masterProj.name());
                            if (masterProj.github() != null) pm.putIfAbsent("github", masterProj.github());
                            if (masterProj.liveDemo() != null) pm.putIfAbsent("liveDemo", masterProj.liveDemo());
                            if (masterProj.techStack() != null) pm.putIfAbsent("techStack", masterProj.techStack());
                        }
                    }
                }
            }

            // Enrich experiences and their projects
            Object experiencesObj = templateData.get("experiences");
            if (experiencesObj instanceof java.util.List<?> expList) {
                for (Object exp : expList) {
                    if (exp instanceof Map<?, ?> expMap) {
                        Map<String, Object> em = (Map<String, Object>) expMap;
                        Object idObj = em.get("id");
                        if (idObj instanceof String id && experienceMap.containsKey(id)) {
                            com.outreach.agent.model.Experience masterExp = experienceMap.get(id);
                            em.putIfAbsent("company", masterExp.company());
                            em.putIfAbsent("title", masterExp.title());
                            em.putIfAbsent("startDate", masterExp.startDate());
                            em.putIfAbsent("endDate", masterExp.endDate());
                            if (masterExp.location() != null) em.putIfAbsent("location", masterExp.location());
                        }

                        // Enrich projects inside experience
                        Object expProjectsObj = em.get("projects");
                        if (expProjectsObj instanceof java.util.List<?> expProjList) {
                            for (Object proj : expProjList) {
                                if (proj instanceof Map<?, ?> projMap) {
                                    Map<String, Object> pm = (Map<String, Object>) projMap;
                                    Object pIdObj = pm.get("id");
                                    if (pIdObj instanceof String pId && expProjectMap.containsKey(pId)) {
                                        com.outreach.agent.model.Project masterProj = expProjectMap.get(pId);
                                        // Some experience projects intentionally have null names, that's fine.
                                        if (masterProj.name() != null) pm.putIfAbsent("name", masterProj.name());
                                        if (masterProj.github() != null) pm.putIfAbsent("github", masterProj.github());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to enrich template data from master resume: {}", ex.getMessage());
        }
    }

    public static class ResumeDataWrapper {
        private Map<String, Object> personalInfo;
        private String summary;
        private java.util.List<Object> skills;
        private java.util.List<Object> experiences;
        private java.util.List<Object> projects;
        private java.util.List<Object> education;
        private java.util.List<Object> certifications;
        private java.util.List<Object> extracurriculars;

        private Map<String, Object> additionalProperties = new java.util.HashMap<>();

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void set(String key, Object value) {
            additionalProperties.put(key, value);
        }

        public Map<String, Object> getPersonalInfo() {
            return personalInfo;
        }

        public void setPersonalInfo(Map<String, Object> personalInfo) {
            this.personalInfo = personalInfo;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public java.util.List<Object> getSkills() {
            return skills;
        }

        public void setSkills(java.util.List<Object> skills) {
            this.skills = skills;
        }

        public java.util.List<Object> getExperiences() {
            return experiences;
        }

        public void setExperiences(java.util.List<Object> experiences) {
            this.experiences = experiences;
        }

        public java.util.List<Object> getProjects() {
            return projects;
        }

        public void setProjects(java.util.List<Object> projects) {
            this.projects = projects;
        }

        public java.util.List<Object> getEducation() {
            return education;
        }

        public void setEducation(java.util.List<Object> education) {
            this.education = education;
        }

        public java.util.List<Object> getCertifications() {
            return certifications;
        }

        public void setCertifications(java.util.List<Object> certifications) {
            this.certifications = certifications;
        }

        public java.util.List<Object> getExtracurriculars() {
            return extracurriculars;
        }

        public void setExtracurriculars(java.util.List<Object> extracurriculars) {
            this.extracurriculars = extracurriculars;
        }

        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new java.util.HashMap<>(additionalProperties);
            if (personalInfo != null)
                map.put("personalInfo", personalInfo);
            if (summary != null)
                map.put("summary", summary);
            if (skills != null)
                map.put("skills", skills);
            if (experiences != null)
                map.put("experiences", experiences);
            if (projects != null)
                map.put("projects", projects);
            if (education != null)
                map.put("education", education);
            if (certifications != null)
                map.put("certifications", certifications);
            if (extracurriculars != null)
                map.put("extracurriculars", extracurriculars);
            return map;
        }
    }
}
