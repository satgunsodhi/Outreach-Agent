package com.outreach.agent.tools;

import com.outreach.agent.service.PdfGeneratorService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class PageLengthCheckerTool {

    private static final Logger log = LoggerFactory.getLogger(PageLengthCheckerTool.class);

    private final PdfGeneratorService pdfGeneratorService;

    public PageLengthCheckerTool(PdfGeneratorService pdfGeneratorService) {
        this.pdfGeneratorService = pdfGeneratorService;
    }

    @Tool("Check the page count and space utilization of a generated PDF resume. Returns page count and fill percentage.")
    public String checkPageLength(String pdfFilePath) {
        try {
            Path path = Paths.get(pdfFilePath);
            if (!Files.exists(path)) {
                return "FAIL: PDF file not found at " + pdfFilePath;
            }
            byte[] pdfBytes = Files.readAllBytes(path);
            int pageCount = pdfGeneratorService.countPages(pdfBytes);

            // Measure fill percentage using the cached fill percent from the last render
            int fillPercent = pdfGeneratorService.getLastRenderedFillPercent();

            if (pageCount > 2) {
                String msg = "FAIL: " + pageCount + " pages (fill: " + fillPercent + "%) — resume is too long. Reduce by removing lowest-priority bullets or dropping a weak project";
                log.info("Resume check failed: {} pages. Agent will retry.", pageCount);
                return msg;
            }

            // 2-page resumes are fully accepted — no underfill check needed (fill will be ~200%)
            if (pageCount == 2) {
                String fillInfo = fillPercent >= 0 ? " (fill: " + fillPercent + "%)" : "";
                log.info("Resume check passed: 2 pages, fill {}%.", fillPercent);
                return "PASS: 2 pages" + fillInfo;
            }

            // Single page — check utilization
            if (fillPercent >= 0 && fillPercent < 89) {
                String msg = "UNDERFILLED: 1 page but only " + fillPercent + "% filled — add more relevant bullet points, include an additional project, or add the extracurriculars section to better utilize the available space";
                log.info("Resume check underfilled: 1 page, fill {}%. Agent will retry to add more content.", fillPercent);
                return msg;
            }

            String fillInfo = fillPercent >= 0 ? " (fill: " + fillPercent + "%)" : "";
            log.info("Resume check passed: 1 page, fill {}%.", fillPercent);
            return "PASS: 1 page" + fillInfo;
        } catch (Exception e) {
            log.warn("Resume check encountered error: {}", e.getMessage());
            return "FAIL: Error checking page length - " + e.getMessage();
        }
    }
}
