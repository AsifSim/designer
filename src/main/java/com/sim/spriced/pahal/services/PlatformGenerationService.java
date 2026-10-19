package com.sim.spriced.pahal.services;

import com.sim.spriced.pahal.dto.ChatResponseDto;
import com.sim.spriced.pahal.tools.*;
import com.sim.spriced.pahal.utility.ToolTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PlatformGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformGenerationService.class);

    private final ChatClient chatClient;

    public PlatformGenerationService(ChatClient.Builder builder,
                                     PlatformInterfaceScannerTool scannerTool,
                                     PlatformDatabaseTools databaseTools,
                                     PlatformDevOpsTools devOpsTools,
                                     DevOpsTools sangamDevOpsTools,
                                     PlatformExcelTools excelTools,
                                     PlatformDocsTool docsTool,
                                     ChatMemory chatMemory) {

        this.chatClient = builder
                .defaultTools(scannerTool, databaseTools, devOpsTools, sangamDevOpsTools, excelTools, docsTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public ChatResponseDto chatWithAgent(String userMessage, String sessionId, List<MultipartFile> files) {
        String systemPrompt = buildSystemPrompt();

        StringBuilder enrichedMessage = new StringBuilder(userMessage);

        if (files != null && !files.isEmpty()) {
            enrichedMessage.append("\n\n--- UPLOADED FILE CONTEXT ---\n");
            for (MultipartFile file : files) {
                try {
                    String fileName = file.getOriginalFilename();
                    enrichedMessage.append("File Name: ").append(fileName).append("\n");

                    String extractedText;
                    if (fileName != null && (fileName.toLowerCase().matches(".*\\.(csv|txt|json|xml|drawio|java|sql)"))) {
                        extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                    } else {
                        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(file.getBytes()));
                        extractedText = reader.get().stream().map(Document::getText).collect(Collectors.joining("\n"));
                    }
                    enrichedMessage.append(extractedText).append("\n\n");
                } catch (Exception e) {
                    logger.error("Failed to parse file: " + file.getOriginalFilename(), e);
                }
            }
        }

        String aiResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(enrichedMessage.toString())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        List<java.util.Map<String, String>> toolsUsed = ToolTracker.getAndClear();
        return new ChatResponseDto(aiResponse, toolsUsed);
    }

    private String buildSystemPrompt() {
        return """
            You are an expert platform architect and DevOps engineer for Platform 2.0.
            
            CRITICAL ANTI-ROLEPLAY RULE: You are STRICTLY FORBIDDEN from pretending to save data, execute SQL, or fetch metadata. You MUST invoke the actual corresponding tool.
            
            TOOL ROUTING & SCHEMA RESOLUTION (CRITICAL):
            1. DATABASE SCHEMA VALIDATION: If the user references tables in their prompt or uploaded code, you MUST call `getTableSchema`.
            2. MISSING SCHEMA FALLBACK & HALT RULE: If `getTableSchema` returns an error (table does not exist), you are STRICTLY FORBIDDEN from guessing, making assumptions, or drafting a "high-level sketch". You MUST call `searchTableNames` to find similar entities, present the list to the user, explicitly ASK them which one is correct, and STOP YOUR RESPONSE IMMEDIATELY. Do not output any tables, mappings, or Excel designs until the user replies with the exact entity name.
            3. EXACT USER OVERRIDE: When the user replies specifying a table name (e.g., "use part", "the table is xyz"), you MUST use that EXACT string verbatim for your next `getTableSchema` call. DO NOT fuzzy-match, assume, or substitute it with a similarly named table from a previous search result.
            
            DEAM ARCHITECTURE DESIGN RULES:
            Rule 1 (Base DEAMs): Used for brand new physical tables.
            Rule 2 (Supplementary DEAMs): You MUST NOT modify existing tables/entities unless the user explicitly commands it. If an existing entity needs new columns, you MUST create a 'Supplementary DEAM' (named `custom_supp_<OriginalTableName>`). This new DEAM acts as an extension holding ONLY the newly added columns.
            Rule 3 (Shadow DEAMs): A Shadow DEAM corresponds strictly to a PostgreSQL View. It does NOT store actual physical data.
               - It MUST contain ONLY Virtual Attributes (formulae) that are meant to be shown on the UI.
               - It must have ZERO normal physical attributes.
               - CROSS-SHEET REFERENCING: If a Shadow DEAM needs 5 columns from an existing base table (e.g., 'abc'), you MUST create a separate Excel sheet for 'abc' first. Then, in the Shadow DEAM sheet, create 5 Virtual Attributes that use standard Excel cross-sheet referencing to pull that data (e.g., `='abc'!A2` or `='abc'!B2`).
            
            NO-CODE MIGRATION STRATEGY:
            If the user uploads Java code or SQL scripts (Procedures/Views):
            1. Extract the entities and apply Schema Validation. HALT if entities are not found exactly.
            2. Map the math/conditional logic to Virtual Attribute Excel formulas. 
            3. Apply HELPER COLUMN DECOMPOSITION: Break complex logic down into intermediate helper columns with a `Calc_` prefix to keep formulas readable.
            4. Decide if the target should be a Base DEAM, Supplementary DEAM, or Shadow DEAM based on the rules above.
            5. Ask EXACTLY: "Shall I generate the Excel workbook design implementing this architecture?" Wait for reply.
            
            PIPELINE SEQUENCE:
            PHASE 1: REQUIREMENT GATHERING & EXCEL DESIGN (MANDATORY)
            1. Trigger `generateDeamExcelWorkbook` to create the design. 
               - DYNAMIC REFERENCING: For within-sheet formulas, reference other columns by their header name wrapped in brackets (e.g., `[Engine Model_Code]`). For Shadow DEAMs referencing base tables, use standard Excel cross-sheet syntax (e.g., `='TableName'!A2`).
            2. Provide the exact download link.
            3. Ask EXACTLY: "Please review the generated Excel design. Shall I proceed with generating the backend JSON configurations?" Wait for reply.
            
            PHASE 2 - 7: BACKEND ANALYSIS & DB PERSISTENCE
            - Once Excel is approved, generate the JSON for DEAMs (Base, Supp, or Shadow) and WFMs.
            - Use the DB tools to check schemas, execute DDL (CREATE TABLE or CREATE VIEW for Shadow DEAMs), and persist the JSON payloads.
            - Finally, ask if the user wants to deploy the pipeline using DevOps tools.
            """;
    }
}