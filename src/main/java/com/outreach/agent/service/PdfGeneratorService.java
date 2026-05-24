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

    private final TemplateEngine templateEngine;
    /** Cached XHTML from the most recent generatePdf call (for fill-% measurement). */
    private volatile String lastRenderedXhtml;

    public PdfGeneratorService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** Returns the XHTML produced by the most recent generatePdf() call, or null. */
    public String getLastRenderedXhtml() {
        return lastRenderedXhtml;
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

        // Normalize bullets before passing to Thymeleaf
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
        this.lastRenderedXhtml = xhtml; // cache for fill-% measurement

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(outputStream);
            return outputStream.toByteArray();
        }
    }

    public int countPages(byte[] pdfBytes) throws Exception {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            return reader.getNumberOfPages();
        }
    }

    /**
     * Measure how much of the A4-page height the content actually fills.
     * Renders onto an extra-tall single page so content never wraps to page 2,
     * then compares the natural content height to the usable area of an A4 page.
     *
     * @return fill percentage 0–100 (can exceed 100 if content overflows 1 page)
     */
    public int measureFillPercentage(String xhtml) throws Exception {
        // A4 = 210×297mm = 595×842pt; margins 0.25in top+bottom → usable ≈ 806pt
        double usableHeight = (297.0 / 25.4 - 0.25 - 0.25) * 72.0; // ≈ 806pt

        // Replace @page size so all content fits on one tall page (no page breaks)
        String tallXhtml = xhtml.replace(
                "size: A4;",
                "size: 210mm 1500mm;"
        );

        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(tallXhtml);
        renderer.layout();

        // rootBox.getHeight() is in Flying Saucer internal dots.
        // Convert: dots → CSS px (÷ dotsPerPixel) → points (× 72/96)
        double contentDots = renderer.getRootBox().getHeight();
        int dpp = renderer.getSharedContext().getDotsPerPixel();
        double contentPt = (contentDots / dpp) * (72.0 / 96.0);

        return Math.max(0, (int) Math.round((contentPt / usableHeight) * 100.0));
    }
}
