package com.outreach.agent.service;

import com.outreach.agent.agent.ResumeAgent;
import org.springframework.stereotype.Service;

@Service
public class ResumeOrchestrationService {

    private final ResumeAgent resumeAgent;
    private final PdfCacheService pdfCacheService;

    public ResumeOrchestrationService(ResumeAgent resumeAgent, PdfCacheService pdfCacheService) {
        this.resumeAgent = resumeAgent;
        this.pdfCacheService = pdfCacheService;
    }

    public String generateTailoredResume(String jobDescription, String companyResearch) {
        // 1. Check Cache
        String cachedPdf = pdfCacheService.getCachedPdfPath(jobDescription, companyResearch);
        if (cachedPdf != null) {
            return cachedPdf; // Skip LLM completely!
        }

        // 2. Generate new PDF
        String pdfPath = resumeAgent.tailorResume(java.util.UUID.randomUUID(), jobDescription, companyResearch);

        // E3: Only cache the result if it looks like a valid file path and the file actually exists.
        // The LLM can return error strings (e.g. "Error generating PDF: ...") — caching those
        // would cause every subsequent call for the same JD to receive a stale error path.
        if (pdfPath != null && !pdfPath.startsWith("Error") && java.nio.file.Files.exists(java.nio.file.Path.of(pdfPath))) {
            pdfCacheService.cachePdfPath(jobDescription, companyResearch, pdfPath);
        }

        return pdfPath;
    }
}
