package com.outreach.agent.service;

import org.openpdf.text.pdf.PdfReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Service
public class PdfGeneratorService {

    private final TemplateEngine templateEngine;

    public PdfGeneratorService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public byte[] generatePdf(Map<String, Object> templateData) throws Exception {
        Context context = new Context();
        context.setVariables(templateData);

        String html = templateEngine.process("resume", context);
        
        // Convert to strict XHTML for Flying Saucer
        Document document = Jsoup.parse(html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        String xhtml = document.html();

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
}
