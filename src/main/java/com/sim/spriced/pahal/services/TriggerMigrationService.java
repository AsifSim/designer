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

@Service
public class TriggerMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(TriggerMigrationService.class);

    @Value("${projects.directory:C:\\Users\\asif.azmi_simadvisor\\Desktop\\Projects}")
    private String projectsDirectory;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ALL 31 TRIGGERS
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

    // Injected PlatformDatabaseTools so LLM can fetch actual DB schemas
    public TriggerMigrationService(ChatClient.Builder builder, ChatMemory chatMemory, PlatformDatabaseTools databaseTools) {
        this.chatClient = builder
                .defaultTools(databaseTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public List<String> listProjects() {
        File dir = new File(projectsDirectory);
        if (!dir.exists() || !dir.isDirectory()) return Collections.emptyList();
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs == null) return Collections.emptyList();
        return Arrays.stream(subdirs).map(File::getName).toList();
    }

    public void runMigration(String sourceProjectName, String newBranchName, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                String sessionId = UUID.randomUUID().toString();
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

                // Overwrite the controller to start fully clean
                Files.writeString(controllerFile, getCleanControllerBase(), StandardCharsets.UTF_8);

                String refServiceCode = getReferenceService();
                String refControllerCode = getReferenceController();

                List<String> accumulatedControllerMethods = new ArrayList<>();
                List<String> accumulatedAutowiredFields = new ArrayList<>();

                int total = TRIGGER_DEFINITIONS.size();
                int current = 0;

                for (TriggerMapping mapping : TRIGGER_DEFINITIONS) {
                    current++;
                    Path processorPath = streamDir.resolve(mapping.processorFileName());

                    if (!Files.exists(processorPath)) {
                        sendSse(emitter, "WARN", String.format("[%d/%d] File %s missing. Skipping.", current, total, mapping.processorFileName()));
                        continue;
                    }

                    sendSse(emitter, "PROGRESS", String.format("[%d/%d] Processing %s (Code + DB DEAMs)...", current, total, mapping.triggerName()));
                    String processorCode = Files.readString(processorPath, StandardCharsets.UTF_8);

                    String aiResponseJson = chatWithMigrationAgent(mapping, processorCode, refServiceCode, refControllerCode, sessionId, emitter);

                    try {
                        JsonNode root = objectMapper.readTree(aiResponseJson);

                        // Extract DB tool verification from LLM and explicitly log it to UI
                        String analyzedTable = root.path("analyzedTable").asText("Unknown");
                        if (!"Unknown".equals(analyzedTable)) {
                            sendSse(emitter, "DB_TOOL", "SUCCESS: LLM executed getTableSchema for DB table [" + analyzedTable + "]");
                            logger.info("LLM reported fetching schema for table: {}", analyzedTable);
                        } else {
                            sendSse(emitter, "WARN", "LLM bypassed the DB Table schema tool for this trigger.");
                        }

                        // 1. Process Java Code
                        String serviceClassName = root.path("serviceClassName").asText();
                        Path newServicePath = serviceImplDir.resolve(serviceClassName + ".java");
                        Files.writeString(newServicePath, root.path("serviceClassCode").asText(), StandardCharsets.UTF_8);

                        accumulatedAutowiredFields.add(root.path("controllerAutowiredField").asText());
                        accumulatedControllerMethods.add(root.path("controllerMethodCode").asText());

                        // 2. Process DEAMs
                        JsonNode deamsNode = root.path("deams");
                        if (deamsNode.isArray() && !deamsNode.isEmpty()) {
                            Path deamsDir = targetDir.resolve("platform_configs/deams");
                            Files.createDirectories(deamsDir);
                            for (JsonNode deam : deamsNode) {
                                String entityName = deam.path("entity").asText(mapping.tableName());
                                Files.writeString(deamsDir.resolve(entityName + ".json"), deam.toPrettyString(), StandardCharsets.UTF_8);
                            }
                            sendSse(emitter, "INFO", "Generated DEAM configurations with Virtual Attributes for " + mapping.tableName());
                        }

                        // 3. Process Workflows
                        JsonNode workflowNode = root.path("workflow");
                        if (!workflowNode.isMissingNode() && !workflowNode.isEmpty()) {
                            Path wfDir = targetDir.resolve("platform_configs/workflows");
                            Files.createDirectories(wfDir);
                            String wfName = workflowNode.path("workflowName").asText(mapping.tableName() + "_workflow");
                            Files.writeString(wfDir.resolve(wfName + ".json"), workflowNode.toPrettyString(), StandardCharsets.UTF_8);
                            sendSse(emitter, "INFO", "Generated Workflow mapping JSON for " + mapping.tableName());
                        }

                        sendSse(emitter, "SUCCESS", "Generated REST endpoint and JSON Configs for " + mapping.triggerName());
                    } catch (Exception ex) {
                        sendSse(emitter, "ERROR", "Failed to parse AI response for " + mapping.triggerName() + ". Error: " + ex.getMessage());
                        logger.error("AI Generation error for {}: response={}", mapping.triggerName(), aiResponseJson, ex);
                    }
                }

                sendSse(emitter, "CONTROLLER", "Appending all endpoints into ProcessorController.java...");
                injectIntoController(controllerFile, accumulatedAutowiredFields, accumulatedControllerMethods);

                sendSse(emitter, "COMPLETE", "Migration completed successfully.");
                emitter.complete();

            } catch (Exception e) {
                logger.error("Migration failed", e);
                sendSse(emitter, "FATAL", "Migration aborted: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });
    }

    private String chatWithMigrationAgent(TriggerMapping mapping, String processorCode, String refService, String refController, String sessionId, SseEmitter emitter) {
        String systemPrompt = buildSystemPrompt(refService, refController);

        StringBuilder enrichedMessage = new StringBuilder();
        enrichedMessage.append("Generate the matching REST Service, Controller endpoint, DEAM JSON, and WFM JSON for the following Trigger.\n\n");
        enrichedMessage.append("Trigger Name: ").append(mapping.triggerName()).append("\n");
        enrichedMessage.append("Target Endpoint: /").append(mapping.endpointPath()).append("\n");
        enrichedMessage.append("Database Target Table: ").append(mapping.tableName()).append("\n\n");
        enrichedMessage.append("--- UPLOADED FILE CONTEXT ---\n");
        enrichedMessage.append("File Name: ").append(mapping.processorFileName()).append("\n");
        enrichedMessage.append(processorCode).append("\n\n");

        String fullPromptText = systemPrompt + "\n\n" + enrichedMessage.toString();

        logger.info("\n\n======================================================================\n");
        logger.info("DISPATCHING FINAL PROMPT TO LLM FOR: {}", mapping.triggerName());
        logger.info("\n{}\n", fullPromptText);
        logger.info("======================================================================\n\n");

        // Push the entire prompt to the UI so the user can see all the data being sent
        sendSse(emitter, "PROMPT", "PROMPT SENT TO LLM:\n----------------------------------------\n" + fullPromptText + "\n----------------------------------------");

        String aiResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(enrichedMessage.toString())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        logger.info("\n\nRAW LLM RESPONSE FOR {}:\n{}\n\n", mapping.triggerName(), aiResponse);

        // BULLETPROOF JSON EXTRACTION
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
               
            3. DEAM JSON CREATION STEPS (CRITICAL):
               - RULE 1: MANDATORY to use the `getTableSchema` tool to fetch actual DB columns.
               - RULE 2: `attributesList` MUST be an array of fully detailed JSON Objects. NEVER output a flat array of strings like `["id", "code"]`.
               - RULE 3: Standard Attribute format: `{"name": "col", "type": "string", "displayName": "Col", "isVirtual": false}`
               - RULE 4: Foreign Key (Physical ID): If a column links to another table (e.g., `target_id` linking to `x_target_table`), you MUST include `"referencedEntity": ["x_target_table"]` and `"referencedAttribute": ["id"]`.
               - RULE 5: Foreign Key (Virtual Ref): Immediately after the Physical ID, create a Virtual Attribute to hold the text representation (e.g., `target_id_ref`) with `"isVirtual": true`, `"type": "string"`, and `"virtualConfig": [{"operator": "CONCAT", "entity": "x_target_table", "attribute": "code"}]`.
               - RULE 6: Include a `deamReads` array with a standard `fetchIdFromCode` filter.

            4. WORKFLOW WFM CREATION STEPS (CRITICAL):
               - RULE 1: `subWorkflowExecution` MUST contain a complete `ttm` block. NEVER leave it empty `[]`.
               - RULE 2: `ttm.payload.sourceMetadata` must list incoming fields, e.g., `[{"ColumnName": {"dataType": "string"}}]`.
               - RULE 3: `ttm.payload.targetMetadata` must map to DEAM, e.g., `{"mapTo": ["db_col"], "dataType": "string", "attributes": ["ColumnName"]}`.
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
              "analyzedTable": "name_of_the_table_fetched_via_tool",
              "serviceClassName": "e.g., DnpricingChangeProcessService",
              "serviceClassCode": "Full Java source code of the service",
              "controllerAutowiredField": "@Autowired private DnpricingChangeProcessService dnpricingChangeProcessService;",
              "controllerMethodCode": "Full Java method code for the controller endpoint",
              "deams": [
                  { 
                      "entity": "table_name", 
                      "deamReads": [
                          { "filter": { "where": [ {"operator": "=", "attributeName": "code"} ], "attributeNames": ["id"] }, "filterName": "fetchIdFromCode" }
                      ],
                      "attributesList": [
                          { "name": "id", "type": "integer", "displayName": "ID", "isVirtual": false },
                          { "name": "target_id", "type": "integer", "displayName": "Target ID", "isVirtual": false, "referencedEntity": ["target_table"], "referencedAttribute": ["id"] },
                          { "name": "target_id_ref", "type": "string", "displayName": "Target Ref", "isVirtual": true, "virtualConfig": [{"operator": "CONCAT", "entity": "target_table", "attribute": "code"}], "referencedEntity": ["target_table"], "referencedAttribute": ["code_name"] }
                      ] 
                  }
              ],
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