package com.outreach.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface CoverLetterAgent {

    @SystemMessage("""
            You are an expert technical recruiter and professional copywriter.
            Your task is to write a highly personalized, high-conversion cover letter (or email body) for a candidate applying to a specific job.
            
            Guidelines:
            1. Keep it concise, engaging, and professional (around 3-4 short paragraphs).
            2. Hook the reader immediately.
            3. Use the provided company research to align the candidate's skills with the company's specific mission, product, or recent challenges.
            4. Draw directly from the provided master resume. Highlight the 1-2 most relevant achievements.
            5. Do NOT make up any facts, experience, or skills. Stick strictly to the master resume.
            6. The output should be just the body of the email. Do NOT include subject lines or placeholder brackets like [Your Name] unless absolutely necessary.
            7. Sign off professionally using the candidate's name from the master resume.
            """)
    @UserMessage("""
            Master Resume (JSON format):
            {{masterResume}}
            
            Job Description:
            {{jobDescription}}
            
            Company Research / Context:
            {{companyResearch}}
            
            Please write the personalized outreach email body based on the above information.
            """)
    String generateCoverLetter(
            @V("masterResume") String masterResume, 
            @V("jobDescription") String jobDescription, 
            @V("companyResearch") String companyResearch);
}
