package com.sim.spriced.pahal.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AngularMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(AngularMigrationService.class);

    @Value("${pahal.workspace.base-dir:C:/Users/asif.azmi_simadvisor/Desktop/Projects}")
    private String baseWorkspaceDir;

    private final ChatClient chatClient;

    // Caches the heavy system prompt per session to avoid re-reading files on every chat message
    private final ConcurrentHashMap<String, String> sessionPrompts = new ConcurrentHashMap<>();

    public AngularMigrationService(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public List<String> getAvailableProjects() {
        List<String> projects = new ArrayList<>();
        Path baseDir = Paths.get(baseWorkspaceDir);

        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            logger.warn("Configured base directory does not exist: {}", baseDir);
            return projects;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    projects.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to list projects in base directory", e);
        }
        return projects;
    }

    public String processMigrationChat(String oldProjectName, String newProjectName, String userMessage, String sessionId) {
        String cacheKey = sessionId + "_" + oldProjectName + "_" + newProjectName;

        String systemPrompt = sessionPrompts.computeIfAbsent(cacheKey, k -> {
            try {
                return buildMegaMigrationPrompt(oldProjectName, newProjectName);
            } catch (IOException e) {
                logger.error("Failed to build migration prompt", e);
                return "You are a migration agent. However, I failed to load the project files due to an error: " + e.getMessage();
            }
        });

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    String buildMegaMigrationPrompt(String oldProjectName, String newProjectName) throws IOException {
        Path basePath = Paths.get(baseWorkspaceDir);
        Path oldProjectPath = basePath.resolve(oldProjectName);
        Path newProjectPath = basePath.resolve(newProjectName);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an Expert UI Architect specializing in legacy codebase migrations to JSON-driven declarative frameworks.\n");
        sb.append("Your goal is to analyze legacy Angular components and translate them purely into the JSON configuration schemas of the TARGET project.\n\n");

        sb.append("=========================================\n");
        sb.append("TARGET PROJECT CONTEXT (JSON FRAMEWORK)\n");
        sb.append("=========================================\n");
        appendProjectContext(newProjectPath, sb, true);

        sb.append("=========================================\n");
        sb.append("LEGACY PROJECT CONTEXT (THE OLD CODEBASE)\n");
        sb.append("=========================================\n");
        appendProjectContext(oldProjectPath, sb, false);

        sb.append("\n=========================================\n");
        sb.append("MIGRATION RULES:\n");
        sb.append("1. STRICT JSON FRAMEWORK RULE: The TARGET project is a declarative UI framework. You are STRICTLY FORBIDDEN from generating Angular TS, HTML, or SCSS for dashboards. You must ONLY output JSON configuration files.\n");
        sb.append("2. EXACT SCHEMA MATCH: Analyze the legacy UI (charts, tables, filters, API endpoints) and map them precisely to the JSON widget structure found in the TARGET project's config examples.\n");
        sb.append("3. COMPLETE OUTPUT: Provide the full, valid JSON configuration blocks for the requested screens.\n");
        sb.append("4. EXPLANATION: Briefly explain how the legacy widgets and API calls were mapped to the new JSON properties.\n");

        return sb.toString();
    }

    private void appendProjectContext(Path projectPath, StringBuilder sb, boolean isTarget) throws IOException {
        if (!Files.exists(projectPath)) {
            sb.append("Project directory not found: ").append(projectPath).append("\n\n");
            return;
        }

        if (isTarget) {
            Path readme = projectPath.resolve("README.md");
            if (Files.exists(readme)) {
                sb.append("--- TARGET PROJECT README ---\n");
                sb.append(Files.readString(readme)).append("\n-----------------------------\n\n");
            }

            sb.append("--- TARGET FRAMEWORK RULES & EXAMPLES ---\n");
            appendTargetImportantFiles(projectPath, sb);
        } else {
            // We skip dumping the old project files entirely.
            // The bulk service injects the specific legacy component into the 'user' prompt on each iteration.
            sb.append("Note: The legacy project architecture is standard Angular. The exact code for each legacy component will be provided in the user prompt during the migration loop.\n\n");
        }
    }

    private void appendTargetImportantFiles(Path root, StringBuilder sb) throws IOException {
        List<Path> targetFiles = new ArrayList<>();
        List<Path> jsonExamples = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("node_modules") || name.equals("dist")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();

                // Grab core app configuration files
                if (name.endsWith("app.routes.ts") || name.endsWith("app.config.ts") || name.equals("package.json")) {
                    if (!name.equals("package-lock.json")) {
                        targetFiles.add(file);
                    }
                }

                // Grab a maximum of 3 JSON files to serve as syntax examples without blowing up the token count
                if (name.endsWith(".json") && !name.equals("package.json") && !name.equals("package-lock.json") && !name.equals("angular.json") && !name.equals("tsconfig.json")) {
                    if (jsonExamples.size() < 3) {
                        jsonExamples.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        targetFiles.addAll(jsonExamples);

        for (Path file : targetFiles) {
            String relativePath = root.relativize(file).toString();
            String lang = file.getFileName().toString().endsWith(".json") ? "json" : "typescript";

            sb.append("#### ").append(relativePath).append("\n```").append(lang).append("\n");
            sb.append(Files.readString(file)).append("\n```\n\n");
        }
    }
}