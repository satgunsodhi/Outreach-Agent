package com.outreach.agent.tools;

import com.outreach.agent.service.PdfGeneratorService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PageLengthCheckerTool {

    private final PdfGeneratorService pdfGeneratorService;

    public PageLengthCheckerTool(PdfGeneratorService pdfGeneratorService) {
        this.pdfGeneratorService = pdfGeneratorService;
    }

    @Tool("Check the page count of a generated PDF resume. Returns the number of pages.")
    public String checkPageLength(String pdfFilePath) {
        try {
            Path path = Paths.get(pdfFilePath);
            if (!Files.exists(path)) {
                return "FAIL: PDF file not found at " + pdfFilePath;
            }
            byte[] pdfBytes = Files.readAllBytes(path);
            int pageCount = pdfGeneratorService.countPages(pdfBytes);
            
            if (pageCount == 1) {
                return "PASS: 1 page";
            } else {
                return "FAIL: " + pageCount + " pages — reduce content by removing lowest-priority bullets or swapping long projects for shorter ones";
            }
        } catch (Exception e) {
            return "FAIL: Error checking page length - " + e.getMessage();
        }
    }
}
