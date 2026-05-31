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
        
        // 3. Save to Cache
        pdfCacheService.cachePdfPath(jobDescription, companyResearch, pdfPath);
        
        return pdfPath;
    }
}
