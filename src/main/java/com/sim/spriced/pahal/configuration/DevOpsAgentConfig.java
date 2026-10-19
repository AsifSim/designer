package com.sim.spriced.pahal.configuration;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DevOpsAgentConfig {

    @Bean
    public ChatMemory chatMemory() {
        // Creates an in-memory chat memory that retains the last 20 messages
        // to prevent the prompt token size from growing infinitely.
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}