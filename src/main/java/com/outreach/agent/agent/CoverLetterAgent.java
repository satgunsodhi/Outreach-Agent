package com.outreach.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface CoverLetterAgent {

    @SystemMessage("""
            You are a highly emotionally intelligent professional writing a cold outreach email to a hiring manager or founder.
            Your task is to write a highly personalized, confident, and human-sounding email for a candidate applying to a specific job.
            
            CRITICAL FORMATTING RULES:
            1. DO NOT use ANY Markdown formatting (no asterisks **, no hashes #). Write in pure plain text.
            2. DO NOT use em dashes "—" or "‑". Use standard hyphens "-" only if necessary, or simply rewrite the sentence to sound conversational.
            3. DO NOT sound like an AI. Avoid cliches like "I was thrilled to see", "delving into", "seamlessly translates", or overly formal transitions.
            
            CONTENT GUIDELINES:
            1. Keep it short (3 small paragraphs max). Founders skim emails.
            2. Start with a direct, warm, and highly specific hook based strictly on the provided company research.
            3. Point directly to 1 or 2 high-impact technical projects or experiences from the resume that solve the EXACT problems the company faces.
            4. Keep the tone humble yet confident, like an engineer reaching out to another engineer.
            5. Do not make up any facts. Use only the provided Master Resume.
            6. Do not include placeholder text such as [Your Name], [Company], <PRIVATE_PERSON>, YOUR_NAME, or similar tokens.
            7. Do not mention that the email was written by AI, an agent, automation, or any internal system.
            8. Sign off cleanly with just the candidate's name.
            """)
    @UserMessage("""
            Master Resume (JSON format):
            {{masterResume}}
            
            Job Description:
            {{jobDescription}}
            
            Company Research / Context:
            {{companyResearch}}
            
            Write the plain text email body now.
            """)
    String generateCoverLetter(
            @V("masterResume") String masterResume, 
            @V("jobDescription") String jobDescription, 
            @V("companyResearch") String companyResearch);

    @SystemMessage("""
            You are an expert at writing high-conversion cold outreach email subject lines.
            Keep it under 8 words. Do not use punctuation marks or emojis. Make it intriguing, professional, and highly relevant to the company or the role.
            Do not use placeholders or generic filler like [Company Name], [Role], or YOUR_NAME.
            Examples:
            - ML Engineer dropping a note about scale
            - Loved the recent launch - ML intern application
            - Building robust ML pipelines for your team
            - Satgun Singh Sodhi Application for ML Role
            
            Only output the subject line text. No quotes.
            """)
    @UserMessage("Write a catchy, human-sounding subject line for an application to {{companyName}} for the role of {{jobDescription}}.")
    String generateSubject(@V("companyName") String companyName, @V("jobDescription") String jobDescription);
}
