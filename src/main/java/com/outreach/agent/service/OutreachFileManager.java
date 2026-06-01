package com.outreach.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutreachFileManager {

    private static final Logger log = LoggerFactory.getLogger(OutreachFileManager.class);

    public void cleanOrphanedPdfs() {
        try {
            Path pdfDir = Path.of("data/generated-pdfs");
            if (Files.exists(pdfDir)) {
                try (java.util.stream.Stream<Path> paths = Files.list(pdfDir)) {
                    paths.filter(p -> p.toString().endsWith(".pdf"))
                         .forEach(p -> {
                             try {
                                 Files.deleteIfExists(p);
                             } catch (Exception e) {
                                 log.debug("Failed to delete old PDF: {}", p);
                             }
                         });
                }
            }
        } catch (Exception e) {
            log.warn("Error cleaning up old PDFs: {}", e.getMessage());
        }
    }

    public void deleteLocalPdf(String pdfPathStr) {
        if (pdfPathStr == null || pdfPathStr.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(pdfPathStr));
        } catch (Exception ex) {
            log.warn("Could not delete local PDF: {}", pdfPathStr);
        }
    }
}
