package com.outreach.agent.dto;

public class ResumeResponse {
    private String pdfFilePath;
    private String message;

    public ResumeResponse() {}

    public ResumeResponse(String pdfFilePath, String message) {
        this.pdfFilePath = pdfFilePath;
        this.message = message;
    }

    public String getPdfFilePath() { return pdfFilePath; }
    public void setPdfFilePath(String pdfFilePath) { this.pdfFilePath = pdfFilePath; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
