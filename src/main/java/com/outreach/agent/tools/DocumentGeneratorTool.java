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
                // Fix #E: write per-generation debug files with a timestamp so batch runs don't overwrite each other.
                java.nio.file.Path debugDir = java.nio.file.Path.of("data/debug");
                if (java.nio.file.Files.exists(debugDir)) {
                    String timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                    String debugJson = objectMapper.writeValueAsString(templateData);
                    java.nio.file.Files.writeString(
                            debugDir.resolve("selected_data_" + timestamp + ".json"),
                            debugJson);
                }
            } catch (Exception ex) {
                // Ignore write failure of debug file
            }

            byte[] pdfBytes = pdfGeneratorService.generatePdf(templateData);
            int pageCount = pdfGeneratorService.countPages(pdfBytes);

            if (pageCount > 1) {
                log.info("Generated PDF has {} pages (exceeds 1-page budget). Starting programmatic optimization...",
                        pageCount);

                // Step 1: Remove extracurriculars to save some space
                if (pageCount > 1 && templateData.containsKey("extracurriculars")) {
                    templateData.remove("extracurriculars");
                    pdfBytes = pdfGeneratorService.generatePdf(templateData);
                    pageCount = pdfGeneratorService.countPages(pdfBytes);
                    log.info("After Step 1 (remove extracurriculars): {} pages", pageCount);
                }

                // Step 2: Reduce bullets per project/experience project to max 3
                if (reduceBullets(templateData, 3)) {
                    pdfBytes = pdfGeneratorService.generatePdf(templateData);
                    pageCount = pdfGeneratorService.countPages(pdfBytes);
                    log.info("After Step 2 (max 3 bullets): {} pages", pageCount);
                }

                // Step 3: Reduce independent projects to max 3
                if (pageCount > 1) {
                    if (reduceProjects(templateData, 3)) {
                        pdfBytes = pdfGeneratorService.generatePdf(templateData);
                        pageCount = pdfGeneratorService.countPages(pdfBytes);
                        log.info("After Step 3 (max 3 projects): {} pages", pageCount);
                    }
                }

                // Step 4: Reduce bullets per project/experience project to max 2
                if (pageCount > 1 && reduceBullets(templateData, 2)) {
                    pdfBytes = pdfGeneratorService.generatePdf(templateData);
                    pageCount = pdfGeneratorService.countPages(pdfBytes);
                    log.info("After Step 4 (max 2 bullets): {} pages", pageCount);
                }

                // Step 5: Trim skills list to max 12 per category
                if (pageCount > 1) {
                    if (trimSkills(templateData, 12)) {
                        pdfBytes = pdfGeneratorService.generatePdf(templateData);
                        pageCount = pdfGeneratorService.countPages(pdfBytes);
                        log.info("After Step 5 (trim skills): {} pages", pageCount);
                    }
                }

                // Step 6: Reduce independent projects to max 2
                if (pageCount > 1) {
                    if (reduceProjects(templateData, 2)) {
                        pdfBytes = pdfGeneratorService.generatePdf(templateData);
                        pageCount = pdfGeneratorService.countPages(pdfBytes);
                        log.info("After Step 6 (max 2 projects): {} pages", pageCount);
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

        public Object getSkills() {
            return skills;
        }

        public void setSkills(Object skills) {
            this.skills = skills;
        }

        public Object getExperiences() {
            return experiences;
        }

        public void setExperiences(Object experiences) {
            this.experiences = experiences;
        }

        public Object getProjects() {
            return projects;
        }

        public void setProjects(Object projects) {
            this.projects = projects;
        }

        public Object getEducation() {
            return education;
        }

        public void setEducation(Object education) {
            this.education = education;
        }

        public Object getCertifications() {
            return certifications;
        }

        public void setCertifications(Object certifications) {
            this.certifications = certifications;
        }

        public Object getExtracurriculars() {
            return extracurriculars;
        }

        public void setExtracurriculars(Object extracurriculars) {
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
