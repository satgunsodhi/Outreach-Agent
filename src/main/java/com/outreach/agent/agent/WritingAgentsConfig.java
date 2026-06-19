package com.outreach.agent.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import com.outreach.agent.tools.DuckDuckGoSearchTool;
import com.outreach.agent.tools.WebScraperTool;

@Configuration
public class WritingAgentsConfig {

        @Bean
        public TargetDiscoveryAgent targetDiscoveryAgent(
                        @Qualifier("writingChatModel") ChatModel chatModel,
                        DuckDuckGoSearchTool searchTool,
                        WebScraperTool scraperTool) {
                return AiServices.builder(TargetDiscoveryAgent.class)
                                .chatModel(chatModel)
                                .tools(searchTool, scraperTool)
                                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                                                request,
                                                "Error: The tool '" + request.name() + "' does not exist. " +
                                                                "If you wanted to search the web, call 'searchWeb'. " +
                                                                "If you wanted to scrape a page, call 'scrapeWebPage'. "
                                                                +
                                                                "Please call a valid tool from the available tools list."))
                                .build();
        }

        @Bean
        public CoverLetterAgent coverLetterAgent(
                        @Qualifier("writingChatModel") ChatModel chatModel) {
                return AiServices.builder(CoverLetterAgent.class)
                                .chatModel(chatModel)
                                .build();
        }

        @Bean
        public CompanyResearchAgent companyResearchAgent(
                        @Qualifier("writingChatModel") ChatModel chatModel,
                        DuckDuckGoSearchTool searchTool,
                        WebScraperTool scraperTool) {
                return AiServices.builder(CompanyResearchAgent.class)
                                .chatModel(chatModel)
                                .tools(searchTool, scraperTool)
                                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                                                request,
                                                "Error: The tool '" + request.name() + "' does not exist. " +
                                                                "If you wanted to search the web, call 'searchWeb'. " +
                                                                "If you wanted to scrape a page, call 'scrapeWebPage'. "
                                                                +
                                                                "Please call a valid tool from the available tools list."))
                                .build();
        }
}
