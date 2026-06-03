package com.outreach.agent.service;

import org.openpdf.text.pdf.PdfReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PdfGeneratorService {

    /** Fix #8: wraps xhtml + fill% in a single record so both are updated atomically via one volatile write. */
    private record RenderResult(String xhtml, int fillPercent) {}
    private volatile RenderResult lastRenderResult = null;

    private final TemplateEngine templateEngine;

    public PdfGeneratorService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** Returns the XHTML produced by the most recent generatePdf() call, or null. */
    public String getLastRenderedXhtml() {
        RenderResult r = lastRenderResult;
        return r != null ? r.xhtml() : null;
    }

    /** Returns the fill percentage of the most recent generatePdf() call, or -1 if not yet generated. */
    public int getLastRenderedFillPercent() {
        RenderResult r = lastRenderResult;
        return r != null ? r.fillPercent() : -1;
    }

    /**
     * Normalize bullet entries: the LLM may return plain strings ("bullet text")
     * or maps ({text: "bullet text", ...}). This ensures Thymeleaf always sees
     * a Map with a 'text' key.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeBullets(List<?> bullets) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (bullets == null)
            return result;
        for (Object bullet : bullets) {
            if (bullet instanceof String s) {
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("text", s);
                result.add(wrapped);
            } else if (bullet instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    /**
     * The LLM sometimes flattens experience bullets onto the experience object
     * instead of nesting them under projects[].bullets as the template expects.
     */
    @SuppressWarnings("unchecked")
    private void normalizeExperiences(Map<String, Object> data) {
        Object raw = data.get("experiences");
        if (raw == null && data.get("experience") instanceof List<?>) {
            raw = data.remove("experience");
            data.put("experiences", raw);
        }
        if (!(raw instanceof List<?> exps)) {
            return;
        }
        for (Object exp : exps) {
            if (!(exp instanceof Map<?, ?> expMap)) {
                continue;
            }
            Map<String, Object> em = (Map<String, Object>) expMap;
            Object projectsObj = em.get("projects");
            boolean hasProjects = projectsObj instanceof List<?> list && !list.isEmpty();
            Object bulletsObj = em.get("bullets");
            if (!(bulletsObj instanceof List<?> expBullets) || expBullets.isEmpty()) {
                continue;
            }
            if (!hasProjects) {
                Map<String, Object> defaultProject = new HashMap<>();
                defaultProject.put("bullets", expBullets);
                em.put("projects", List.of(defaultProject));
                em.remove("bullets");
                continue;
            }
            if (projectsObj instanceof List<?> projects) {
                boolean anyProjectHasBullets = false;
                for (Object proj : projects) {
                    if (proj instanceof Map<?, ?> projMap) {
                        Object projBullets = ((Map<String, Object>) projMap).get("bullets");
                        if (projBullets instanceof List<?> list && !list.isEmpty()) {
                            anyProjectHasBullets = true;
                            break;
                        }
                    }
                }
                if (!anyProjectHasBullets && !projects.isEmpty()
                        && projects.get(0) instanceof Map<?, ?> firstProj) {
                    ((Map<String, Object>) firstProj).put("bullets", expBullets);
                    em.remove("bullets");
                }
            }
        }
    }

    /**
     * Recursively normalize all bullet lists within the template data map.
     */
    @SuppressWarnings("unchecked")
    private void normalizeBulletsInData(Map<String, Object> data) {
        // Normalize top-level sections that have a bullets list: projects,
        // extracurriculars
        for (String sectionKey : List.of("projects", "extracurriculars")) {
            if (data.get(sectionKey) instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Map<String, Object> m = (Map<String, Object>) itemMap;
                        if (m.get("bullets") instanceof List<?> bullets) {
                            m.put("bullets", normalizeBullets(bullets));
                        }
                    }
                }
            }
        }
        // Normalize experience -> projects -> bullets
        if (data.get("experiences") instanceof List<?> exps) {
            for (Object exp : exps) {
                if (exp instanceof Map<?, ?> expMap) {
                    Map<String, Object> em = (Map<String, Object>) expMap;
                    if (em.get("projects") instanceof List<?> expProjects) {
                        for (Object proj : expProjects) {
                            if (proj instanceof Map<?, ?> projMap) {
                                Map<String, Object> pm = (Map<String, Object>) projMap;
                                if (pm.get("bullets") instanceof List<?> bullets) {
                                    pm.put("bullets", normalizeBullets(bullets));
                                }
                            }
                        }
                    }
                }
            }
        }
        // Normalize skills: ensure each skill category's 'items' is a proper
        // List<String>
        if (data.get("skills") instanceof List<?> skills) {
            for (Object skill : skills) {
                if (skill instanceof Map<?, ?> skillMap) {
                    Map<String, Object> sm = (Map<String, Object>) skillMap;
                    Object items = sm.get("items");
                    if (items != null && !(items instanceof List)) {
                        // If it's some other collection type, wrap in a new list
                        sm.put("items", new ArrayList<>(List.of(items.toString())));
                    }
                }
            }
        }
    }

    private Object cleanData(Object obj) {
        if (obj instanceof String s) {
            return s.replace("\u2011", "-") // non-breaking hyphen
                    .replace("\u00ad", "") // soft hyphen
                    .replace("\u200b", "") // zero-width space
                    .replace("\u00a0", " ") // non-breaking space
                    .replace("\u202f", " "); // narrow no-break space
        } else if (obj instanceof Map<?, ?> m) {
            Map<String, Object> copy = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                String key = entry.getKey().toString();
                copy.put(key, cleanData(entry.getValue()));
            }
            return copy;
        } else if (obj instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            for (Object item : l) {
                copy.add(cleanData(item));
            }
            return copy;
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    public byte[] generatePdf(Map<String, Object> templateData) throws Exception {
        // Clean special/non-breaking characters from the entire dataset
        templateData = (Map<String, Object>) cleanData(templateData);

        // Fix flattened experience structure, then normalize bullet shapes
        normalizeExperiences(templateData);
        normalizeBulletsInData(templateData);

        Context context = new Context();
        context.setVariables(templateData);

        String html = templateEngine.process("resume", context);

        // Parse as XML (Thymeleaf already outputs valid XHTML) so Jsoup preserves
        // inline elements like <b> and <strong> rather than self-closing them.
        Document document = Jsoup.parse(html, "", org.jsoup.parser.Parser.htmlParser());
        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .prettyPrint(false)
                .charset(java.nio.charset.StandardCharsets.UTF_8);
        String xhtml = document.html();

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();

            // Calculate fill percentage using the renderer's layout state
            double usableHeight = (297.0 / 25.4 - 0.25 - 0.25) * 72.0; // ≈ 806pt for A4 with margins
            double contentPt = (renderer.getRootBox().getHeight() / (double) renderer.getSharedContext().getDotsPerPixel()) * (72.0 / 96.0);
            // Fix #8: write both fields atomically as a single record reference
            this.lastRenderResult = new RenderResult(xhtml, Math.max(0, (int) Math.round((contentPt / usableHeight) * 100.0)));

            renderer.createPDF(outputStream);
            return outputStream.toByteArray();
        }
    }

    public int countPages(byte[] pdfBytes) throws Exception {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            return reader.getNumberOfPages();
        }
    }


}
