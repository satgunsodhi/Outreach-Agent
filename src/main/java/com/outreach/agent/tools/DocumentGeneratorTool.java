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

    public DocumentGeneratorTool(PdfGeneratorService pdfGeneratorService, ObjectMapper objectMapper) {
        this.pdfGeneratorService = pdfGeneratorService;
        this.objectMapper = objectMapper;
    }

    @Tool("Generate a PDF resume from selected resume data. Returns the file path of the generated PDF.")
    public String generateResume(String selectedDataJson) {
        try {
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("C:/Users/Satgu/.gemini/antigravity-ide/scratch/last_selected_data.json"),
                    selectedDataJson
                );
            } catch (Exception ex) {
                // Ignore write failure of debug file
            }

            String sanitizedJson = selectedDataJson
                .replace("\u2011", "-")  // Replace non-breaking hyphen with standard hyphen
                .replace("\u00A0", " "); // Replace non-breaking space with standard space
            
            Map<String, Object> templateData = objectMapper.readValue(sanitizedJson, new TypeReference<Map<String, Object>>() {});
            byte[] pdfBytes = pdfGeneratorService.generatePdf(templateData);
            
            Path tempFile = Files.createTempFile("resume-", ".pdf");
            Files.write(tempFile, pdfBytes);
            
            return tempFile.toAbsolutePath().toString().replace("\\", "/");
        } catch (Exception e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }
}
