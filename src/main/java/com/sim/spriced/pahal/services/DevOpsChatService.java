package com.sim.spriced.pahal.services;

import com.sim.spriced.pahal.tools.DevOpsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class DevOpsChatService {

    private final ChatClient chatClient;

    public DevOpsChatService(ChatClient.Builder builder, DevOpsTools tools, ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultSystem("""
                You are an expert DevOps Agentic AI. 
                Your goal is to assist the user in deploying Platform 2.0 and the application.
                Follow these interactive steps strictly in order. Do not skip ahead:
                
                1. Call `getAvailableVms` to list available targets (including localhost). Ask the user whether they want to deploy locally or to a specific remote VM.
                2. Wait for the user's target selection.
                3. Ask the user which branch of the repo (spriced-application-2.0.101) they want to deploy.
                4. Wait for the user's response.
                5. Ask the user for the Database connection details (e.g., JDBC URL).
                6. Wait for the user's response.
                7. Once you have the target (local or VM), branch, and DB details, invoke `deployPlatform`.
                8. After the platform builds successfully, invoke `deployApplication`.
                9. Finally, use `monitorHealth` to verify the container is running properly.
                10. Report the final deployment status to the user.
                """)
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String chat(String userMessage, String sessionId) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}