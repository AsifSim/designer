package com.sim.spriced.pahal.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AdaptiveCardService {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveCardService.class);
    private final ChatClient chatClient;

    public AdaptiveCardService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String generateAdaptiveCard(String sourceJson) {
        logger.debug("Received request to convert JSON to Adaptive Card");

        return chatClient.prompt()
                .system(buildSystemPrompt())
                .user(sourceJson)
                .call()
                .content();
    }

    private String buildSystemPrompt() {
        return """
            You are an expert UI developer and data visualization specialist.
            Your task is to take a custom JSON structure and strictly convert it into a valid Microsoft Adaptive Card JSON (Version 1.5).
            
            MAPPING RULES:
            1. EXCLUSION RULE (CRITICAL): You MUST completely ignore the `requestData` object and any root-level metadata (such as `entity`, `workflowId`, `version`, `businessId`, `commitOffset`, `commitStatus`, `endpoints`, `error`). Do NOT generate any TextBlocks or FactSets for these fields.
            2. INCLUSION RULE: Focus EXCLUSIVELY on processing and visualizing the data located inside the `responseList` array. Extract the nested properties (e.g., from `ui-data` -> `data`) and represent them in the card.
            3. Simple key-value pairs and flat objects within the response data should be grouped into a `Container` and represented using a `FactSet`.
            4. Arrays of objects (representing tables, such as `engine_model`) should be mapped to `ColumnSet` blocks with `Column` items defining the table headers and subsequent rows of data.
            5. Add relevant `TextBlock` headers (with `weight: Bolder` and `size: Large/Medium`) for distinct sections of the response data.
            6. The root schema MUST strictly contain: "type": "AdaptiveCard", "$schema": "http://adaptivecards.io/schemas/adaptive-card.json", and "version": "1.5".
            
            STRICT OUTPUT RULES:
            - Return ONLY the raw, perfectly valid JSON object.
            - DO NOT wrap the output in markdown code blocks (e.g., do not use ```json ... ```).
            - DO NOT include comments, conversational text, or explanations.
            """;
    }
}