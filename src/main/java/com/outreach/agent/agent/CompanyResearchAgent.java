package com.outreach.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CompanyResearchAgent {

    @SystemMessage("""
            You are an expert corporate researcher.
            Your task is to analyze the provided URL or company name by scraping its website and extracting key information.
            Focus on finding:
            1. The company's core product or service.
            2. The company's mission or core values.
            3. Any recent news, challenges, or technical stack mentioned.
            Summarize this research in a concise format (2-3 paragraphs) that can be used later to personalize a cover letter.
            If the URL fails to scrape, try to provide any general knowledge you might have about the company.
            """)
    String researchCompany(@UserMessage String companyNameOrUrl);
}
