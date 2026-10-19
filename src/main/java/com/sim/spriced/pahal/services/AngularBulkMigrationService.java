package com.sim.spriced.pahal.services;

import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AngularBulkMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(AngularBulkMigrationService.class);

    @Value("${pahal.workspace.base-dir:C:/Users/asif.azmi_simadvisor/Desktop/Projects}")
    private String baseWorkspaceDir;

    private final ChatClient chatClient;
    private final AngularMigrationService contextService;

    public AngularBulkMigrationService(ChatClient.Builder builder, AngularMigrationService contextService) {
        this.chatClient = builder.build(); // Stateless client for bulk processing
        this.contextService = contextService;
    }

    public void executeBulkMigration(String oldProject, String newProject, SseEmitter emitter) {
        Path basePath = Paths.get(baseWorkspaceDir);
        Path oldProjectPath = basePath.resolve(oldProject);
        Path sourceNewProjectPath = basePath.resolve(newProject);

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        Path outputDir = basePath.resolve(newProject + "_migration_workspace_" + uniqueId);

        try {
            // 1. Clone Target Repo & Setup Branches
            sendEvent(emitter, "info", "Creating new workspace for target repo: " + outputDir.getFileName());

            try (Git git = Git.cloneRepository()
                    .setURI(sourceNewProjectPath.toUri().toString())
                    .setDirectory(outputDir.toFile())
                    .call()) {

                sendEvent(emitter, "info", "Attempting to checkout 'develop' branch...");
                try {
                    git.checkout().setName("develop").call();
                } catch (Exception e) {
                    sendEvent(emitter, "info", "'develop' branch not found locally. Branching from current default branch instead.");
                }

                String newBranchName = "feature/migrate-nrp-screens-" + uniqueId;
                sendEvent(emitter, "info", "Creating and switching to new branch: " + newBranchName);
                git.checkout()
                        .setCreateBranch(true)
                        .setName(newBranchName)
                        .call();

                // 2. Fetch the target architecture context (Automatically includes README.md)
                sendEvent(emitter, "info", "Analyzing target JSON architecture and README from " + newProject + "...");
                String targetContext = contextService.buildMegaMigrationPrompt(oldProject, newProject);

                // 3. Scan old project for components
                sendEvent(emitter, "info", "Scanning legacy project for NRP screens and components...");
                List<Path> legacyComponents = findLegacyComponents(oldProjectPath);
                int total = legacyComponents.size();

                if (total == 0) {
                    sendEvent(emitter, "error", "No Angular components (*.component.ts) found in " + oldProject);
                    emitter.complete();
                    return;
                }

                // 4. Iterate and Migrate
                for (int i = 0; i < total; i++) {
                    Path compPath = legacyComponents.get(i);
                    String compName = compPath.getFileName().toString().replace(".component.ts", "");

                    sendEvent(emitter, "progress", String.format("{\"current\": %d, \"total\": %d, \"file\": \"%s\"}", i + 1, total, compName));

                    String componentCode = readComponentBundle(compPath);
                    String aiResponse = migrateComponentViaLLM(compName, componentCode, targetContext);

                    saveGeneratedFiles(outputDir, compName, aiResponse);
                }

                // 5. Stage the newly created JSON files
                sendEvent(emitter, "info", "Staging new JSON configurations to Git index...");
                git.add().addFilepattern(".").call();

                sendEvent(emitter, "complete", "Migration complete! Workspace ready at: " + outputDir.toAbsolutePath() + " (Branch: " + newBranchName + ")");
                emitter.complete();
            }

        } catch (Exception e) {
            logger.error("Bulk migration failed", e);
            sendEvent(emitter, "error", "Migration failed: " + e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private String migrateComponentViaLLM(String componentName, String componentCode, String targetContext) {
        String prompt = targetContext + "\n\n" +
                "You are migrating the legacy component: " + componentName + ".\n" +
                "Here is the legacy code bundle (TS, HTML, SCSS):\n" + componentCode + "\n\n" +
                "MIGRATION COMMAND (CRITICAL):\n" +
                "The TARGET PROJECT is a declarative JSON-driven UI framework. You MUST NOT generate Angular TS/HTML/SCSS files.\n" +
                "Analyze the legacy screen's layout, charts, API calls, and filters, and translate them entirely into the JSON configuration schema expected by the target project.\n\n" +
                "FILE PATH RESOLUTION:\n" +
                "Consult the TARGET PROJECT README provided in your context. Find the section defining where 'Dashboard Configuration' files belong (e.g., foundry/foundry-app/public/assets/dashboard-config/).\n" +
                "Extract that exact path and use it to format your output.\n\n" +
                "OUTPUT FORMAT RULE:\n" +
                "You MUST output the new JSON configuration file using the exact delimiter structure below so a script can parse it. Replace <PATH_FROM_README> with the directory you found in the documentation.\n" +
                "@@file: <PATH_FROM_README>/" + componentName + "-config.json@@\n" +
                "{\n  \"dashboard-name\": \"Migrated Dashboard\",\n  \"pageContent\": [ ... ]\n}\n" +
                "@@end@@";

        return chatClient.prompt().user(prompt).call().content();
    }

    private void saveGeneratedFiles(Path outputDir, String compName, String aiResponse) throws IOException {
        Pattern pattern = Pattern.compile("@@file:\\s*(.*?)\\s*@@(.*?)@@end@@", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(aiResponse);

        boolean foundFiles = false;
        while (matcher.find()) {
            foundFiles = true;
            String relativePath = matcher.group(1).trim();
            String content = matcher.group(2).trim();

            Path targetFile = outputDir.resolve(relativePath);
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Saved migrated file: {}", targetFile);
        }

        if (!foundFiles) {
            Path dumpFile = outputDir.resolve("unparsed_dumps/raw_dump_" + compName + ".json");
            Files.createDirectories(dumpFile.getParent());
            Files.writeString(dumpFile, aiResponse, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private List<Path> findLegacyComponents(Path root) throws IOException {
        List<Path> components = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("node_modules") || name.equals("dist") || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".component.ts")) {
                    components.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return components;
    }

    private String readComponentBundle(Path tsFile) {
        StringBuilder bundle = new StringBuilder();
        String basePath = tsFile.toString().replace(".component.ts", "");

        appendIfExists(bundle, tsFile, "TYPESCRIPT");
        appendIfExists(bundle, Paths.get(basePath + ".component.html"), "HTML");
        appendIfExists(bundle, Paths.get(basePath + ".component.scss"), "SCSS");
        appendIfExists(bundle, Paths.get(basePath + ".component.css"), "CSS");

        return bundle.toString();
    }

    private void appendIfExists(StringBuilder bundle, Path file, String label) {
        if (Files.exists(file)) {
            try {
                bundle.append("--- ").append(label).append(" (").append(file.getFileName()).append(") ---\n");
                bundle.append(Files.readString(file)).append("\n\n");
            } catch (IOException ignored) {}
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}