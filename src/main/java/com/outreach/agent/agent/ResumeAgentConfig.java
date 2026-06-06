package com.outreach.agent.agent;

import com.outreach.agent.tools.DocumentGeneratorTool;
import com.outreach.agent.tools.PageLengthCheckerTool;
import com.outreach.agent.tools.ProjectDeepContextTool;
import com.outreach.agent.tools.ResumeKnowledgeBaseTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResumeAgentConfig {

    @Bean
    public ResumeAgent resumeAgent(
            @org.springframework.beans.factory.annotation.Qualifier("resumeChatModel") ChatModel chatModel,
            ResumeKnowledgeBaseTool knowledgeBaseTool,
            DocumentGeneratorTool documentGeneratorTool,
            PageLengthCheckerTool pageLengthCheckerTool,
            ProjectDeepContextTool deepContextTool) {
        
        return AiServices.builder(ResumeAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryId -> MessageWindowChatMemory.withMaxMessages(40))
                .tools(knowledgeBaseTool, documentGeneratorTool, pageLengthCheckerTool, deepContextTool)
                .build();
    }
}

