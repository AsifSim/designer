package com.sim.spriced.pahal.services;

import com.sim.spriced.pahal.dto.ArchitecturePlanResponse;
import com.sim.spriced.pahal.tools.PlatformDatabaseTools;
import com.sim.spriced.pahal.tools.PlatformInterfaceScannerTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ArchitectureDecisionService {

    private static final Logger logger = LoggerFactory.getLogger(ArchitectureDecisionService.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final RestClient restClient;
    private final ChatClient chatClient;

    private final PlatformInterfaceScannerTool scannerTool;
    private final PlatformDatabaseTools databaseTools;
    private final DeamGeneratorService deamGeneratorService;

    private static final String IBOB_README_URL = "https://api.github.com/repos/simaiserver/spriced-platform-ib-ob/contents/README.md?ref=dev-baseline";
    private static final String DML_README_URL = "https://api.github.com/repos/simaiserver/spriced-platform-data-management-layer/contents/README.md?ref=dev-baseline";

    public ArchitectureDecisionService(ChatClient.Builder chatClientBuilder,
                                       PlatformInterfaceScannerTool scannerTool,
                                       PlatformDatabaseTools databaseTools,
                                       DeamGeneratorService deamGeneratorService) {
        this.restClient = RestClient.create();
        this.chatClient = chatClientBuilder.build();
        this.scannerTool = scannerTool;
        this.databaseTools = databaseTools;
        this.deamGeneratorService = deamGeneratorService;
    }

    public ArchitecturePlanResponse generateArchitecturePlan(String flowDescription, List<MultipartFile> files) {
        String ibobDocs = fetchReadme(IBOB_README_URL);
        String dmlDocs = fetchReadme(DML_README_URL);

        String systemPrompt = buildSystemPrompt(ibobDocs, dmlDocs);
        logger.debug("systemPrompt = {}", systemPrompt);

        StringBuilder enrichedMessage = new StringBuilder(flowDescription);

        // Process Uploaded Files with Tika Bypass for Text/CSV
        if (files != null && !files.isEmpty()) {
            enrichedMessage.append("\n\n--- UPLOADED FILE CONTEXT ---\n");
            for (MultipartFile file : files) {
                try {
                    String fileName = file.getOriginalFilename();
                    enrichedMessage.append("File Name: ").append(fileName).append("\n");

                    String extractedText;
                    if (fileName != null && (fileName.toLowerCase().endsWith(".csv") || fileName.toLowerCase().endsWith(".txt") || fileName.toLowerCase().endsWith(".json"))) {
                        // FAST BYPASS: Read plain text formats natively to avoid Tika's UnsynchronizedBufferedReader crash
                        extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                    } else {
                        // TIKA FALLBACK: Parse complex documents (.docx, .xlsx, .pdf)
                        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(file.getBytes()));
                        extractedText = reader.get().stream()
                                .map(Document::getText)
                                .collect(Collectors.joining("\n"));
                    }

                    enrichedMessage.append(extractedText).append("\n\n");
                } catch (Exception e) {
                    logger.error("Failed to parse file: " + file.getOriginalFilename(), e);
                    enrichedMessage.append("[Error reading file content]\n\n");
                }
            }
            enrichedMessage.append("-----------------------------\n");
        }

        return chatClient.prompt()
                .system(systemPrompt)
                .user(enrichedMessage.toString())
                .tools(scannerTool, databaseTools, this)
                .call()
                .entity(ArchitecturePlanResponse.class);
    }

    @Tool(description = "Generates and retrieves the base DEAM JSON for a given database table, automatically extracting column metadata and cross-entity foreign key virtual attributes. Use this FIRST to understand the base structure before calculating extra virtual attributes.")
    public String generateBaseDeamWithForeignKeys(@ToolParam(description = "The exact database table name") String tableName) {
        try {
            logger.info("LLM triggered generation of base DEAM with FKs for table: {}", tableName);
            Map<String, String> result = deamGeneratorService.generateAndSaveNewDeam(tableName);
            return result.get("json");
        } catch (Exception e) {
            logger.error("Error generating base DEAM with foreign keys for table: {}", tableName, e);
            return "Error: " + e.getMessage();
        }
    }

    private String fetchReadme(String url) {
        try {
            String token = getGithubToken();
            if (token == null || token.isEmpty()) {
                logger.error("GitHub token is null or empty.");
                return "Documentation fetch failed due to missing auth.";
            }

            return restClient.get().uri(url)
                    .header("Accept", "application/vnd.github.v3.raw")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            logger.error("Failed to fetch docs from {}", url, e);
            return "Documentation fetch failed.";
        }
    }

    private String getGithubToken() {
        Path standardPath = Paths.get(System.getProperty("user.home"), ".github_token");
        Path tokenFile = null;

        if (Files.exists(standardPath)) {
            tokenFile = standardPath;
        } else if (IS_WINDOWS) {
            Path wslPath = Paths.get("\\\\wsl$\\Ubuntu\\home\\asif\\.github_token");
            if (Files.exists(wslPath)) {
                tokenFile = wslPath;
            }
        }

        if (tokenFile != null) {
            try {
                return Files.readString(tokenFile).trim();
            } catch (Exception e) {
                logger.error("Failed to read token from path: {}", tokenFile, e);
            }
        }
        return null;
    }

    private String buildSystemPrompt(String ibobDocs, String dmlDocs) {
        return """
            You are an expert platform architect and code generator for Platform 2.0. 
            You communicate strictly via the defined JSON response schema.
            
            PHASE 1: ANALYSIS
            If the user describes a new flow, evaluate it. Populate the required component counts, explain your decisions in the `architecturalDecisions` array, and set `agentMessage` to exactly: "Do you want to proceed with actual DEAM, WFM, and Excel generation?"
            Do NOT generate `generatedDeamJson` or `generatedWfmJson` yet.
            
            PHASE 2: GENERATION & EXECUTION
            If the user's prompt indicates they want to proceed based on previous context, you MUST execute these steps in exact order:
            1. MANDATORY FIRST STEP: You MUST call the `generateBaseDeamWithForeignKeys` tool to fetch the base database structure. DO NOT use `getTableSchema` to verify tables for DEAM architecture.
            2. Use the `executeDdl` tool ONLY if brand new physical PostgreSQL base tables are required.
            3. Use the `generateDeamExcelWorkbook` tool to create the Excel representation of the DEAMs.
            4. Use `upsertEntityStore` and `upsertWorkflowStore` to save configurations.
            5. Populate `generatedDeamJson` and `generatedWfmJson` in your final JSON response.
            
            --- PLATFORM CONTEXT ---
            IB-OB (Workflow Orchestrator) Docs:
            %s
            
            DML (Data Management Layer) Docs:
            %s
            
            --- DEAM GENERATION RULES ---
            1. Every DEAM JSON map generated MUST include the following mandatory attributes in its `attributesList`: created_date, created_by, updated_date, updated_by, is_valid, uuid, validationstatus.
            2. The `deamReads` array must ALWAYS contain at least one dynamic filter where `"filterType": "dynamic"` and `"filterName": "dynamic_filter"`.
            
            --- VIRTUAL ATTRIBUTES (SAVA RULES) & SHADOW DEAMS (CRITICAL) ---
            1. After using `generateBaseDeamWithForeignKeys`, check if the user requires OTHER virtual attributes (e.g., non-foreign-key computations, UI formulas).
            2. If new virtual attributes are required for an ALREADY EXISTING table, DO NOT append them to the existing table's DEAM. You MUST create a completely new DEAM named `shadow_<original_table_name>`. This `shadow_<original_table_name>` must contain ONLY the new virtual attributes. The Excel sheet must also be named `shadow_<original_table_name>`.
            3. VIRTUAL ATTRIBUTE JSON FORMAT: You MUST format all newly generated virtual attributes exactly like this:
               
               For Arithmetic/Formulas/Concat:
               {
                 "name": "calculated_field",
                 "type": "double",
                 "isVirtual": true,
                 "versioned": false,
                 "virtualConfig": [
                   { "scope": "", "entity": "", "operator": "*", "attribute": "column_a" },
                   { "scope": "", "entity": "", "operator": "", "attribute": "column_b" }
                 ]
               }
               
               For Cross-Entity Lookups (if explicitly required in shadow DEAM):
               {
                 "name": "target_ref",
                 "type": "string",
                 "isVirtual": true,
                 "versioned": false,
                 "virtualConfig": [
                   { "entity": "target_table", "attribute": "code" }
                 ]
               }
            """.formatted(ibobDocs, dmlDocs);
    }
}