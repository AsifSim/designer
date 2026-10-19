package com.sim.spriced.pahal.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sim.spriced.pahal.tools.PlatformDatabaseTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TriggerMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(TriggerMigrationService.class);

    @Value("${pahal.workspace.base-dir:C:/Users/asif.azmi_simadvisor/Desktop/Projects}")
    private String projectsDirectory;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Injecting the native DEAM Generator we built
    private final DeamGeneratorService deamGeneratorService;

    // In-memory registry to hold paused sessions awaiting user input
    private final Map<String, MigrationSessionContext> sessionRegistry = new ConcurrentHashMap<>();

    private static final List<TriggerMapping> TRIGGER_DEFINITIONS = List.of(
            new TriggerMapping("PART_ENTITY_CHANGE", "PartEntityProcessor.java", "part_entity_change", "part_entity"),
            new TriggerMapping("DNPRICING_CHANGE", "DNPricingEntityProcessor.java", "dnpricing_change", "dnpricing"),
            new TriggerMapping("P_PRICING_ACTION_OEM_CHANGE", "OEMPricingEntityProcessor.java", "p_pricing_action_oem_change", "p_pricing_action_oem"),
            new TriggerMapping("PART_PRICE_LIST_END_CHANGE", "UpdatePartPriceStatus.java", "part_price_list_end_change", "part_price_list"),
            new TriggerMapping("PVC_ACTION_CHANGE", "PVCActionEntityProcessor.java", "pvc_action_change", "pvc_action"),
            new TriggerMapping("LOCAL_PART_PRICING_CHANGE", "LocalPartPricingEntityProcessor.java", "local_part_pricing_change", "local_part_pricing"),
            new TriggerMapping("SEGEMENT_ACTION_CHANGE", "SegementEntityProcessor.java", "segement_action_change", "segment_action"),
            new TriggerMapping("PRICE_LIST_ENTITY_CHANGED", "PricelistReleaseEntityProcessor.java", "price_list_entity_changed", "price_list"),
            new TriggerMapping("REGIONAL_SEGMENT_CHANGED", "RegionalSegmentEntityProcessor.java", "regional_segment_changed", "regional_segment"),
            new TriggerMapping("LOCAL_PART_XREF_CHANGE", "LocalPartXrefEntityProcessor.java", "local_part_xref_change", "local_part_xref"),
            new TriggerMapping("BASE_PRICE_CHANGE", "BasePriceEntityProcessor.java", "base_price_change", "base_price"),
            new TriggerMapping("BASE_FILE_CHANGE", "BaseFileProcessor.java", "base_file_change", "base_file"),
            new TriggerMapping("CHANNEL_CODE_CHANGE", "ChannelCodeProcessor.java", "channel_code_change", "channel_code"),
            new TriggerMapping("PART_NOUN_NAME_ECC_XREF_CHANGE", "PartNounNameEccxRefEntitySubProcessor.java", "part_noun_name_ecc_xref_change", "part_noun_name_ecc_xref"),
            new TriggerMapping("XCPM_ASSIGNMENT_LOGIC", "XCPMAssignmentLogicSubProcessor.java", "xcpm_assignment_logic", "xcpm_assignment"),
            new TriggerMapping("P_PRICING_ACTION_CHANGE", "PricingActionEntitySubProcessor.java", "p_pricing_action_change", "p_pricing_action"),
            new TriggerMapping("X_CPM_CHANGE", "CPMEntitySubProcessor.java", "x_cpm_change", "cpm"),
            new TriggerMapping("XCHANGE_REASON", "ChangeReasonEntitySubProcessor.java", "xchange_reason", "change_reason"),
            new TriggerMapping("QUOTE_CHANGE", "QuoteEntitySubProcessor.java", "quote_change", "quote"),
            new TriggerMapping("CHANNEL_PRICING_CHANGE", "ChannelPricingEntityProcessor.java", "channel_pricing_change", "channel_pricing"),
            new TriggerMapping("QUOTE_DETAIL", "QuoteDetailEntityProcessor.java", "quote_detail", "quote_detail"),
            new TriggerMapping("PART_OEM_CHANGE", "PartOEMEntityProcessor.java", "part_oem_change", "part_oem"),
            new TriggerMapping("CHANNEL_MULTI_CHANGE", "ChannelMultipilerGomsProcessor.java", "channel_multi_change", "channel_multi"),
            new TriggerMapping("XUPTIME_PARTS", "XUpTimePartEntityProcessor.java", "xuptime_parts", "xuptime_parts"),
            new TriggerMapping("CERTIFICATION_LEVEL", "CertificationLevelEntityProcessor.java", "certification_level", "certification_level"),
            new TriggerMapping("S_CURRENCY_CHANGE", "CurrencySubProcessor.java", "s_currency_change", "currency"),
            new TriggerMapping("S_QUOTES_CURRENCY_CHANGE", "QuotesCurrencySubprocessor.java", "s_quotes_currency_change", "quotes_currency"),
            new TriggerMapping("XDN_PRICING_ADDITIONS_CHANGE", "XDNPricingAdditionsProcessor.java", "xdn_pricing_additions_change", "xdn_pricing_additions"),
            new TriggerMapping("LOCAL_JV_SEGMENT_TYPE_OVERRIDE", "LocalJVSegmentTypeOverrideSubprocessor.java", "local_jv_segment_type_override", "local_jv_segment_type_override"),
            new TriggerMapping("CES_TP_NEW_PARTS", "CESTpNewPartsProcessor.java", "ces_tp_new_parts", "ces_tp_new_parts"),
            new TriggerMapping("CES_EBU_PART_XREF_CHANGE", "CESEbupartXrefLeafProcessor.java", "ces_ebu_part_xref_change", "ces_ebu_part_xref")
    );

    public record TriggerMapping(String triggerName, String processorFileName, String endpointPath, String tableName) {}

    private static class MigrationSessionContext {
        String sessionId;
        String sourceProjectName;
        String newBranchName;
        SseEmitter emitter;
        Path targetDir;
        Path serviceImplDir;
        Path streamDir;
        Path controllerFile;
        List<String> accumulatedControllerMethods;
        List<String> accumulatedAutowiredFields;
        int currentIndex;

        MigrationSessionContext(String sessionId, String sourceProjectName, String newBranchName, SseEmitter emitter, Path targetDir, Path serviceImplDir, Path streamDir, Path controllerFile) {
            this.sessionId = sessionId;
            this.sourceProjectName = sourceProjectName;
            this.newBranchName = newBranchName;
            this.emitter = emitter;
            this.targetDir = targetDir;
            this.serviceImplDir = serviceImplDir;
            this.streamDir = streamDir;
            this.controllerFile = controllerFile;
            this.accumulatedControllerMethods = new ArrayList<>();
            this.accumulatedAutowiredFields = new ArrayList<>();
            this.currentIndex = 0;
        }
    }

    private static class InteractivePromptException extends RuntimeException {
        private final String triggerName;
        private final String tableName;
        InteractivePromptException(String triggerName, String tableName, String message) {
            super(message);
            this.triggerName = triggerName;
            this.tableName = tableName;
        }
        public String getTriggerName() { return triggerName; }
        public String getTableName() { return tableName; }
    }

    public TriggerMigrationService(ChatClient.Builder builder, ChatMemory chatMemory, PlatformDatabaseTools databaseTools, DeamGeneratorService deamGeneratorService) {
        this.deamGeneratorService = deamGeneratorService;
        this.chatClient = builder
                .defaultTools(databaseTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public List<String> listProjects() {
        try {
            if (projectsDirectory == null || projectsDirectory.trim().isEmpty()) {
                logger.warn("projectsDirectory property is null or empty");
                return Collections.emptyList();
            }
            File dir = new File(projectsDirectory);
            if (!dir.exists() || !dir.isDirectory()) {
                return Collections.emptyList();
            }
            File[] subdirs = dir.listFiles(File::isDirectory);
            if (subdirs == null) return Collections.emptyList();
            return Arrays.stream(subdirs).map(File::getName).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error reading projects directory: ", e);
            return Collections.emptyList();
        }
    }

    public String runMigration(String sourceProjectName, String newBranchName, SseEmitter emitter) {
        String sessionId = UUID.randomUUID().toString();

        CompletableFuture.runAsync(() -> {
            try {
                sendSse(emitter, "INIT", "Starting migration for project: " + sourceProjectName);

                Path sourceDir = Paths.get(projectsDirectory, sourceProjectName);
                String targetProjectName = sourceProjectName + "-" + newBranchName.replaceAll("[^a-zA-Z0-9-_]", "-");
                Path targetDir = Paths.get(projectsDirectory, targetProjectName);

                sendSse(emitter, "COPY", "Copying project folder to: " + targetProjectName);
                copyDirectory(sourceDir, targetDir);

                sendSse(emitter, "GIT", "Checking out new branch: " + newBranchName);
                executeGitBranch(targetDir, newBranchName);

                Path streamDir = targetDir.resolve("src/main/java/com/cummins/spriced/parts/pricing/pipeline/streams");
                Path serviceImplDir = targetDir.resolve("src/main/java/com/cummins/spriced/parts/pricing/service/impl");
                Path controllerFile = targetDir.resolve("src/main/java/com/cummins/spriced/parts/pricing/controller/ProcessorController.java");

                Files.createDirectories(serviceImplDir);
                Files.writeString(controllerFile, getCleanControllerBase(), StandardCharsets.UTF_8);

                MigrationSessionContext ctx = new MigrationSessionContext(sessionId, sourceProjectName, newBranchName, emitter, targetDir, serviceImplDir, streamDir, controllerFile);
                sessionRegistry.put(sessionId, ctx);

                processLoop(sessionId, null);

            } catch (Exception e) {
                logger.error("Migration failed", e);
                sendSse(emitter, "FATAL", "Migration aborted: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return sessionId;
    }

    public void resumeMigration(String sessionId, String userOverrideTableName) {
        CompletableFuture.runAsync(() -> {
            try {
                processLoop(sessionId, userOverrideTableName);
            } catch (Exception e) {
                logger.error("Resume failed", e);
            }
        });
    }

    private void processLoop(String sessionId, String userOverrideTableName) {
        MigrationSessionContext ctx = sessionRegistry.get(sessionId);
        if (ctx == null) return;

        int total = TRIGGER_DEFINITIONS.size();
        String refServiceCode = getReferenceService();
        String refControllerCode = getReferenceController();

        try {
            while (ctx.currentIndex < total) {
                TriggerMapping mapping = TRIGGER_DEFINITIONS.get(ctx.currentIndex);
                Path processorPath = ctx.streamDir.resolve(mapping.processorFileName());

                if (!Files.exists(processorPath)) {
                    sendSse(ctx.emitter, "WARN", String.format("[%d/%d] File %s missing. Skipping.", ctx.currentIndex + 1, total, mapping.processorFileName()));
                    ctx.currentIndex++;
                    continue;
                }

                sendSse(ctx.emitter, "PROGRESS", String.format("[%d/%d] Processing %s (Code + DB DEAMs)...", ctx.currentIndex + 1, total, mapping.triggerName()));
                String processorCode = Files.readString(processorPath, StandardCharsets.UTF_8);

                try {
                    String aiResponseJson = chatWithMigrationAgent(mapping, processorCode, refServiceCode, refControllerCode, ctx.sessionId, ctx.emitter, userOverrideTableName);

                    // SKIP logic validation
                    boolean isSkipped = "SKIP".equalsIgnoreCase(userOverrideTableName);

                    // Clear override after using it once
                    userOverrideTableName = null;

                    JsonNode root = objectMapper.readTree(aiResponseJson);

                    String analyzedTable = root.path("analyzedTable").asText("Unknown");

                    if (!isSkipped && ("Unknown".equals(analyzedTable) || analyzedTable.contains("schema not found"))) {
                        // Pass both TriggerName and TableName
                        throw new InteractivePromptException(mapping.triggerName(), mapping.tableName(), "Table schema could not be verified by tools.");
                    }

                    if (!isSkipped) {
                        sendSse(ctx.emitter, "DB_TOOL", "SUCCESS: LLM executed getTableSchema for DB table [" + analyzedTable + "]");
                        logger.info("LLM reported fetching schema for table: {}", analyzedTable);
                    } else {
                        sendSse(ctx.emitter, "INFO", "Bypassing DEAM & WFM Generation due to SKIP override.");
                    }

                    // 1. Process Java Code
                    String serviceClassName = root.path("serviceClassName").asText();
                    Path newServicePath = ctx.serviceImplDir.resolve(serviceClassName + ".java");
                    Files.writeString(newServicePath, root.path("serviceClassCode").asText(), StandardCharsets.UTF_8);

                    ctx.accumulatedAutowiredFields.add(root.path("controllerAutowiredField").asText());
                    ctx.accumulatedControllerMethods.add(root.path("controllerMethodCode").asText());

                    // 2. Process DEAMs using our Native Java Generator
                    if (!isSkipped) {
                        Path deamsDir = ctx.targetDir.resolve("platform_configs/deams");
                        Files.createDirectories(deamsDir);
                        try {
                            // Generate, DB Upsert, and return JSON directly via our service
                            Map<String, String> deamResult = deamGeneratorService.generateAndSaveNewDeam(analyzedTable);
                            String deamJsonString = deamResult.get("json");

                            Files.writeString(deamsDir.resolve(analyzedTable + ".json"), deamJsonString, StandardCharsets.UTF_8);
                            sendSse(ctx.emitter, "INFO", "Natively generated DEAM configurations with Virtual Attributes for " + analyzedTable);
                        } catch (Exception e) {
                            logger.error("Failed to generate DEAM natively for table: {}", analyzedTable, e);
                            sendSse(ctx.emitter, "WARN", "Native DEAM generation failed for " + analyzedTable + ": " + e.getMessage());
                        }
                    }

                    // 3. Process Workflows
                    JsonNode workflowNode = root.path("workflow");
                    if (!isSkipped && workflowNode != null && !workflowNode.isMissingNode() && !workflowNode.isEmpty()) {
                        Path wfDir = ctx.targetDir.resolve("platform_configs/workflows");
                        Files.createDirectories(wfDir);
                        String wfName = workflowNode.path("workflowName").asText(mapping.tableName() + "_workflow");
                        Files.writeString(wfDir.resolve(wfName + ".json"), workflowNode.toPrettyString(), StandardCharsets.UTF_8);
                        sendSse(ctx.emitter, "INFO", "Generated Workflow mapping JSON for " + mapping.tableName());
                    }

                    sendSse(ctx.emitter, "SUCCESS", "Generated REST endpoint and JSON Configs for " + mapping.triggerName());

                    ctx.currentIndex++;

                } catch (InteractivePromptException e) {
                    sendSse(ctx.emitter, "WARN", "Tool Execution Halted: " + e.getMessage());
                    // Pass Trigger Name | Table Name to UI
                    sendSse(ctx.emitter, "INPUT_REQUIRED", e.getTriggerName() + "|" + e.getTableName());
                    return; // PAUSE THE LOOP
                } catch (Exception ex) {
                    sendSse(ctx.emitter, "ERROR", "Failed to parse AI response for " + mapping.triggerName() + ". Check Spring Console.");
                    logger.error("AI Generation error for {}: ", mapping.triggerName(), ex);
                    ctx.currentIndex++;
                }
            }

            sendSse(ctx.emitter, "CONTROLLER", "Appending all endpoints into ProcessorController.java...");
            injectIntoController(ctx.controllerFile, ctx.accumulatedAutowiredFields, ctx.accumulatedControllerMethods);

            sendSse(ctx.emitter, "COMPLETE", "Migration completed successfully.");
            ctx.emitter.complete();
            sessionRegistry.remove(sessionId);

        } catch (Exception e) {
            logger.error("Process loop failed", e);
            sendSse(ctx.emitter, "FATAL", "Migration aborted: " + e.getMessage());
            ctx.emitter.completeWithError(e);
            sessionRegistry.remove(sessionId);
        }
    }

    private String chatWithMigrationAgent(TriggerMapping mapping, String processorCode, String refService, String refController, String sessionId, SseEmitter emitter, String userOverrideTableName) {
        String systemPrompt = buildSystemPrompt(refService, refController);

        StringBuilder enrichedMessage = new StringBuilder();
        enrichedMessage.append("Generate the matching REST Service, Controller endpoint, and WFM JSON for the following Trigger.\n\n");
        enrichedMessage.append("Trigger Name: ").append(mapping.triggerName()).append("\n");
        enrichedMessage.append("Target Endpoint: /").append(mapping.endpointPath()).append("\n");

        String targetTable = mapping.tableName().toLowerCase();

        if ("SKIP".equalsIgnoreCase(userOverrideTableName)) {
            enrichedMessage.append("Database Target Table: NONE. THIS IS A LOGICAL TRIGGER.\n");
            enrichedMessage.append("CRITICAL OVERRIDE: DO NOT generate any Workflows for this trigger. Leave the 'workflow' JSON object completely EMPTY. ONLY generate the Service and Controller.\n\n");
        } else if (userOverrideTableName != null && !userOverrideTableName.trim().isEmpty()) {
            targetTable = userOverrideTableName.trim().toLowerCase();
            enrichedMessage.append("Database Target Table: USE THIS EXACT OVERRIDE TABLE NAME FOR TOOLS -> ").append(targetTable).append("\n\n");
        } else {
            enrichedMessage.append("Database Target Table: ").append(targetTable).append("\n\n");
        }

        enrichedMessage.append("--- UPLOADED FILE CONTEXT ---\n");
        enrichedMessage.append("File Name: ").append(mapping.processorFileName()).append("\n");
        enrichedMessage.append(processorCode).append("\n\n");

        enrichedMessage.append("File Name: EntityConstants.java\n");
        enrichedMessage.append(getEntityConstantsCode()).append("\n\n");

        String fullPromptText = systemPrompt + "\n\n" + enrichedMessage.toString();

        logger.info("\n\n======================================================================\n");
        logger.info("DISPATCHING FINAL PROMPT TO LLM FOR: {}", mapping.triggerName());
        logger.info("\n{}\n", fullPromptText);
        logger.info("======================================================================\n\n");

        sendSse(emitter, "PROMPT", "PROMPT SENT TO LLM (Check Spring console for full payload)");

        String aiResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(enrichedMessage.toString())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        logger.info("\n\nRAW LLM RESPONSE FOR {}:\n{}\n\n", mapping.triggerName(), aiResponse);

        int startIndex = aiResponse.indexOf('{');
        int endIndex = aiResponse.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
            return aiResponse.substring(startIndex, endIndex + 1);
        }

        return aiResponse;
    }

    private String buildSystemPrompt(String refService, String refController) {
        return """
            You are an expert Spring Boot Architect migrating Kafka Streams to Platform 2.0 REST pipelines.
            
1. JAVA SERVICE GENERATION:

- Extract @Autowired dependencies and logic inside `createSubTopology()`.

- Output a strictly typed Spring @Service matching the Reference Service below.

               
            2. DOWNSTREAM CASCADING RULE:
               If the original processor invokes a downstream sub-processor, replace it with the new `*ProcessService` equivalent (e.g., `LocalPartPricingChangeProcessService.process(...)`). NEVER autowire Kafka classes.
               
            3. SCHEMA VALIDATION (CRITICAL):
- RULE 1: Use `EntityConstants.java` to identify the actual database table name used in the processor code. It is MANDATORY to use the `getTableSchema` tool to fetch actual DB columns. You MUST pass the resolved table name to the tool in STRICTLY LOWERCASE. If the table is not found, output "schema not found" in the "analyzedTable" field and halt structure generation.


            4. WORKFLOW WFM CREATION STEPS (CRITICAL):
- RULE 1: `subWorkflowExecution` MUST contain a complete `ttm` block. NEVER leave it empty `[]`.

- RULE 2: `ttm.payload.sourceMetadata` must list incoming fields, e.g., `[{"ColumnName": {"dataType": "string"}}]`.

- RULE 3: `ttm.payload.targetMetadata` must map to DB columns, e.g., `{"mapTo": ["db_col"], "dataType": "string", "attributes": ["ColumnName"]}`.

- RULE 4: Foreign Key Lookups (DEAM_READ): If mapping a string code to a DB ID, use `transformations` inside `targetMetadata`:

                 `"transformations": { "operations": [ { "step": "1", "operation": "DEAM_READ", "entityName": "target_table", "attributeList": [{"sourceAttribute": "StringCode", "targetAttribute": "code"}], "fetchAttributes": [{"sourceField": "id", "output": "db_col_id"}] } ] }`

            REFERENCE PATTERNS:
            Service Reference:
            ```java
            %s
            ```
            
            Controller Endpoint Reference:
            ```java
            %s
            ```
            
            OUTPUT FORMAT (STRICT JSON ONLY):
            Reply with ONLY the following JSON structure filled with the generated data. NO Markdown, NO conversational text.
            {
              "analyzedTable": "name_of_the_table_fetched_via_tool_OR_schema_not_found",
              "serviceClassName": "e.g., DnpricingChangeProcessService",
              "serviceClassCode": "Full Java source code of the service",
              "controllerAutowiredField": "@Autowired private DnpricingChangeProcessService dnpricingChangeProcessService;",
              "controllerMethodCode": "Full Java method code for the controller endpoint",
              "workflow": {
                  "workflowName": "TRIGGER_NAME_HERE",
                  "subWorkflowExecution": [
                      { 
                         "ttm": { 
                             "payload": { 
                                 "sourceMetadata": [ { "IncomingCode": { "dataType": "string" } } ], 
                                 "targetMetadata": [
                                     {
                                         "mapTo": ["target_id"],
                                         "dataType": "integer",
                                         "attributes": ["IncomingCode"],
                                         "transformations": {
                                             "operations": [
                                                 { "step": "1", "operation": "DEAM_READ", "entityName": "target_table", "attributeList": [{"sourceAttribute": "IncomingCode", "targetAttribute": "code"}], "fetchAttributes": [{"sourceField": "id", "output": "target_id"}] }
                                             ]
                                         }
                                     }
                                 ] 
                             } 
                         }, 
                         "sinkType": "GRPC_SINK",
                         "crud": {"operation": "upsert"}
                      }
                  ]
              }
            }
            """.formatted(refService, refController);
    }

    private String getEntityConstantsCode() {
        return """
            package com.cummins.spriced.parts.pricing.constants;

            public class EntityConstants {
                public static final String PART_ENTITY_NAME = "part";
                public static final String SEGMENT_ENTITY_NAME = "segment";
                public static final String PRICE_LIST_RELEASE_ENTITY = "price_list_release";
                public static final String DN_PRICING_ENTITY_NAME ="dn_pricing";

                public static final String P_PRICING_ACTION_OEM ="p_pricing_action_oem";
                public static final String PART_PRICE_LIST_END_DATE ="part_price_list_end_date";

                public static final String PVC_ACTION_ENTITY_NAME = "pvc_action";
                public static final String LOCAL_PART_PRICING = "local_part_pricing";
                public static final String LOCAL_SEGMENT = "local_segment";
                public static final String LOCAL_PART_XREF = "local_part_xref";
                public static final String CHANNEL_PRICING = "channel_pricing";
                public static final String BASE_PRICE_ENTITY_NAME = "base_price";

                public static final String BASE_FILE_ENTITY_NAME = "p_base_file";
                public static final String CHANNEL_CODE_ENTITY_NAME = "p_channel_code";
                public static final String PART_NOUN_NAME_ECC_XREF = "part_noun_name_ecc_xref";
                public static final String XCPM_ASSIGNAMENT_LOGIC = "xcpm_assignment_logic";
                public static final String P_PRICING_ACTION = "p_pricing_action";
                public static final String XPRICING_ACTION_MASTER = "xpricing_action_master";
                public static final String CHANGE_REASON = "xchange_reason";
                public static final String CHANGE_REASON_HISTORICAL = "xchange_reason_historical";
                public static final String CPM = "xcpm";
                public static final String XCPM_HISTORICAL = "xcpm_historical";
                public static final String QUOTE = "quote";
                public static final String QUOTE_DETAIL = "quote_detail";
                public static final Object PRICE_LIST_DETAIL = "price_list_detail";
                public static final String NRP_INT_OB_PVC = "nrp_int_ob_pvc";
                public static final String PVC_ACTION_DETAIL = "p_pvc_action_detail";
                public static final String PRICE_LIST_RELEASE = "price_list_release";
                public static final String QUOTE_APPROVAL_HISTORY = "quote_approval_history";
                public static final String PART_OEM = "part_oem";
                public static final String CHANNEL_MULTI_GOMS = "p_channel_multiplier_goms";
                public static final String XUPTIME_PARTS = "xuptime_parts";
                public static final String CERTIFICATION_LEVEL = "xcertification_level";
                public static final String SUGGESTED_ENGIN_MODEL ="xcjv_suggested_engine_model";
                public static final String S_CURRENCY = "s_currency";
                public static final String P_PRICING_ACTION_DETAIL_ENTITY_NAME = "p_pricing_action_detail";
                public static final String S_QUOTES_CURRENCY = "s_quotes_currency";
                public static final String XDN_PRICING_ADDITIONS = "xdn_pricing_additions";
                public static final String LOCAL_JV_SEGMENT_TYPE_OVERRIDE = "local_jv_segment_type_override";
                public static final String CES_TRANSFER_PRICING = "ces_dn_pricing";
                public static final String CES_TP_NEW_PARTS = "ces_tp_new_parts";
                public static final String CES_EBUPART_XREF_LEAF = "ces_ebupart_xref_leaf";
                public static final String CES_TRANSFER_HISTORY = "ces_pricing_history_leaf";
                public static final String CODE = "code";

            }
            """;
    }

    private String getCleanControllerBase() {
        return """
            package com.cummins.spriced.parts.pricing.controller;

            import java.util.Map;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;

            import com.cummins.spriced.parts.pricing.pipeline.service.impl.CommonServices;
            import com.cummins.spriced.parts.pricing.service.impl.*;

            @RestController
            @RequestMapping("/api/v1/pricing")
            public class ProcessorController extends CommonServices {
                
                private static final Logger logger = LoggerFactory.getLogger(ProcessorController.class);

            }
            """;
    }

    private String getReferenceService() {
        return """
            package com.cummins.spriced.parts.pricing.service.impl;

            import com.cummins.spriced.parts.pricing.pipeline.models.PVCAction;
            import com.cummins.spriced.parts.pricing.pipeline.repo.IPVCActionRepo;
            import com.cummins.spriced.parts.pricing.pipeline.service.IPVCActionEntityService;
            import java.util.Map;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;

            @Service
            public class PvcActionChangeProcessService {

                @Autowired
                private IPVCActionRepo ipvcActionRepo;
                @Autowired
                private IPVCActionEntityService pvcActionEntityService;

                private static final Logger logger = LoggerFactory.getLogger(PvcActionChangeProcessService.class);

                public void process(Map<String, Object> request) {
                        PVCAction pvcAction = PVCAction.builder().pvcActionData(request).build();
                        
                       logger.info("********* STARTED Processing Entity ************" );
                        try {
                            pvcAction.setPvcActionData(this.ipvcActionRepo.getViewData(pvcAction.getId()));
                            this.pvcActionEntityService.updatePVCActionRecord(pvcAction);
                         logger.info("********* FINISHED Processing Entity ************" );
                        } catch (Exception ex) {
                            logger.error("Error", ex);
                        }
                }
            }
            """;
    }

    private String getReferenceController() {
        return """
            @Autowired
            private PvcActionChangeProcessService pvcActionChangeProcessService;

            @PostMapping("/pvc_action_change")
            public ResponseEntity<?> pvcActionChangeProcess(@RequestBody Map<String, Object> request) {
                logger.info("pvcActionChangeProcess is triggered");
                pvcActionChangeProcessService.process(request);
                logger.info("pvcActionChangeProcess starting");
                upsertDataInDB(request, "pvc_action", false);
                logger.info("pvcActionChangeProcess finished");
                return ResponseEntity.ok("Success");
            }
            """;
    }

    private void injectIntoController(Path controllerPath, List<String> fields, List<String> methods) throws IOException {
        if (!Files.exists(controllerPath)) return;
        String content = Files.readString(controllerPath, StandardCharsets.UTF_8);

        int lastBraceIndex = content.lastIndexOf("}");
        if (lastBraceIndex == -1) return;

        StringBuilder injection = new StringBuilder("\n\n    // ====== AUTO-GENERATED MIGRATED TRIGGER ENDPOINTS ======\n\n");
        fields.forEach(f -> injection.append("    ").append(f.trim()).append("\n"));
        injection.append("\n");
        methods.forEach(m -> injection.append("    ").append(m.trim().replaceAll("(?m)^", "    ")).append("\n\n"));

        Files.writeString(controllerPath, content.substring(0, lastBraceIndex) + injection + "}\n", StandardCharsets.UTF_8);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) deleteDirectory(target);
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dest)) Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private void executeGitBranch(Path repoDir, String branchName) {
        try {
            File gitDir = repoDir.resolve(".git").toFile();
            if (!gitDir.exists()) {
                new ProcessBuilder("git", "init").directory(repoDir.toFile()).start().waitFor();
                new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
                new ProcessBuilder("git", "commit", "-m", "Init").directory(repoDir.toFile()).start().waitFor();
            }
            new ProcessBuilder("git", "checkout", "-b", branchName).directory(repoDir.toFile()).start().waitFor();
        } catch (Exception ex) {
            logger.warn("Git branch execution failed: {}", ex.getMessage());
        }
    }

    private void sendSse(SseEmitter emitter, String stage, String message) {
        try {
            emitter.send(SseEmitter.event().name("status").data(Map.of("stage", stage, "message", message)));
        } catch (Exception ignored) {}
    }
}