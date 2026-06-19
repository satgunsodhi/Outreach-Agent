package com.outreach.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutreachFileManager {

    private static final Logger log = LoggerFactory.getLogger(OutreachFileManager.class);

    private final PdfCacheService pdfCacheService;

    public OutreachFileManager(PdfCacheService pdfCacheService) {
        this.pdfCacheService = pdfCacheService;
    }

    /**
     * E7: Removes orphaned PDF files from the generated-pdfs directory at the start of each
     * batch run. Uses {@code Files.isRegularFile} and a name-pattern check to avoid
     * accidentally deleting non-PDF artifacts (e.g. pdf-cache.json).
     */
    public void cleanOrphanedPdfs() {
        try {
            Path pdfDir = Path.of("data/generated-pdfs");
            if (Files.exists(pdfDir)) {
                try (java.util.stream.Stream<Path> paths = Files.list(pdfDir)) {
                    paths.filter(p -> Files.isRegularFile(p)
                                 && p.getFileName().toString().matches("resume-.*\\.pdf"))
                         .forEach(p -> {
                             try {
                                 // B1/B3: invalidate the cache entry before deleting the file.
                                 pdfCacheService.invalidatePath(p.toAbsolutePath().toString().replace("\\", "/"));
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
            // B3: invalidate the cache entry for this path before deleting it.
            pdfCacheService.invalidatePath(pdfPathStr);
            Files.deleteIfExists(Path.of(pdfPathStr));
        } catch (Exception ex) {
            log.warn("Could not delete local PDF: {}", pdfPathStr);
        }
    }
}
