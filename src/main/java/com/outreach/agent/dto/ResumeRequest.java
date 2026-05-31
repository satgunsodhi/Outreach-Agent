package com.outreach.agent.dto;

public class ResumeRequest {
    private String jobDescription;
    private String companyResearch;

    public ResumeRequest() {}

    public String getJobDescription() { return jobDescription; }
    public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }

    public String getCompanyResearch() { return companyResearch; }
    public void setCompanyResearch(String companyResearch) { this.companyResearch = companyResearch; }
}
