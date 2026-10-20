//package com.sim.spriced.pahal.services;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.eclipse.jgit.api.Git;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//
//import java.io.File;
//import java.io.IOException;
//import java.nio.file.*;
//import java.nio.file.attribute.BasicFileAttributes;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.UUID;
//import java.util.stream.Stream;
//
//@Service
//public class AngularContextService {
//
//    private static final Logger logger = LoggerFactory.getLogger(AngularContextService.class);
//    private static final String CONTEXT_DIR = System.getProperty("user.dir") + "/generated_contexts";
//
//    private static final Path LOCAL_PROJECT_PATH = Paths.get("C:/Users/asif.azmi_simadvisor/Desktop/Projects/spriced-ui");
//
//    public String generateLlmsTxt(String repoUrl, String branchName) throws Exception {
//        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
//        String outputFileName = "llms_" + uniqueId + ".txt";
//
//        Path outputDir = Paths.get(CONTEXT_DIR);
//        if (!Files.exists(outputDir)) {
//            Files.createDirectories(outputDir);
//        }
//
//        Path targetRepoDir;
//        boolean isLocalManual = Files.exists(LOCAL_PROJECT_PATH);
//
//        if (isLocalManual) {
//            logger.info("Found local repository at {}. Bypassing remote git clone.", LOCAL_PROJECT_PATH);
//            targetRepoDir = LOCAL_PROJECT_PATH;
//
//            if (branchName != null && !branchName.trim().isEmpty()) {
//                switchLocalBranchIfPossible(targetRepoDir, branchName.trim());
//            }
//        } else {
//            logger.info("Local path {} not found. Falling back to cloning from {}", LOCAL_PROJECT_PATH, repoUrl);
//            targetRepoDir = Paths.get(System.getProperty("user.dir"), "temp_angular_repo_" + uniqueId);
//            checkoutRepository(repoUrl, branchName, targetRepoDir);
//        }
//
//        try {
//            String context = buildContext(targetRepoDir);
//            Path outputPath = outputDir.resolve(outputFileName);
//            Files.writeString(outputPath, context, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
//            logger.info("Successfully generated context file at: {}", outputPath);
//            return outputFileName;
//        } finally {
//            if (!isLocalManual && Files.exists(targetRepoDir)) {
//                deleteDirectoryRecursively(targetRepoDir);
//            }
//        }
//    }
//
//    private void switchLocalBranchIfPossible(Path repoDir, String branchName) {
//        File gitDir = repoDir.resolve(".git").toFile();
//        if (gitDir.exists()) {
//            try (Git git = Git.open(repoDir.toFile())) {
//                logger.info("Attempting to checkout local branch '{}'", branchName);
//                git.checkout().setName(branchName).call();
//            } catch (Exception e) {
//                logger.warn("Could not switch to branch '{}' locally: {}. Reading current branch state as is.", branchName, e.getMessage());
//            }
//        }
//    }
//
//    private void checkoutRepository(String repoUrl, String branchName, Path targetDir) throws Exception {
//        if (!repoUrl.endsWith(".git")) {
//            repoUrl += ".git";
//        }
//
//        logger.info("Cloning repository {} (branch: {})", repoUrl, branchName);
//        String token = getGithubToken();
//
//        org.eclipse.jgit.api.CloneCommand cloneCommand = Git.cloneRepository()
//                .setURI(repoUrl)
//                .setDirectory(targetDir.toFile())
//                .setBranch(branchName);
//
//        if (token != null && !token.isEmpty() && repoUrl.contains("github.com")) {
//            cloneCommand.setCredentialsProvider(new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", token));
//        }
//
//        try (Git git = cloneCommand.call()) {
//            logger.info("Clone complete.");
//        }
//    }
//
//    private String buildContext(Path repoDir) throws IOException {
//        StringBuilder sb = new StringBuilder();
//        sb.append("# Angular Project Context (llms.txt)\n\n");
//
//        appendProjectOverviewAndArchitecture(repoDir, sb);
//
//        // Dynamically locate source directories
//        List<Path> appDirectories = findAppDirectories(repoDir);
//
//        if (appDirectories.isEmpty()) {
//            sb.append("## Directory Structure\n```text\nNo standard src or app directories found.\n```\n\n");
//        } else {
//            for (Path appDir : appDirectories) {
//                String relativePath = repoDir.relativize(appDir).toString();
//                sb.append("## Target Application Directory: `").append(relativePath).append("`\n\n");
//
//                appendDirectoryStructure(appDir, sb);
//                appendImportantFiles(appDir, sb);
//            }
//        }
//
//        return sb.toString();
//    }
//
//    // Recursively searches for 'src/app' or 'src' folders while ignoring heavy build/dependency folders
//    private List<Path> findAppDirectories(Path repoDir) throws IOException {
//        List<Path> appDirs = new ArrayList<>();
//
//        Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
//            @Override
//            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
//                String name = dir.getFileName().toString();
//                // Skip non-source folders
//                if (name.equals("node_modules") || name.equals("dist") || name.equals(".git") || name.equals(".angular")) {
//                    return FileVisitResult.SKIP_SUBTREE;
//                }
//                // Locate standard Angular App paths
//                if (dir.endsWith(Paths.get("src", "app"))) {
//                    appDirs.add(dir);
//                    return FileVisitResult.SKIP_SUBTREE;
//                }
//                return FileVisitResult.CONTINUE;
//            }
//        });
//
//        // Fallback: If 'src/app' isn't found, look for just 'src'
//        if (appDirs.isEmpty()) {
//            Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
//                @Override
//                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
//                    String name = dir.getFileName().toString();
//                    if (name.equals("node_modules") || name.equals("dist") || name.equals(".git")) {
//                        return FileVisitResult.SKIP_SUBTREE;
//                    }
//                    if (dir.endsWith("src")) {
//                        appDirs.add(dir);
//                        return FileVisitResult.SKIP_SUBTREE;
//                    }
//                    return FileVisitResult.CONTINUE;
//                }
//            });
//        }
//
//        // Ultimate Fallback: Just return the root if no src is found
//        if (appDirs.isEmpty()) {
//            appDirs.add(repoDir);
//        }
//
//        return appDirs;
//    }
//
//    private void appendProjectOverviewAndArchitecture(Path repoDir, StringBuilder sb) {
//        sb.append("## Project Overview & Key Architectural Decisions\n");
//        Path packageJsonPath = repoDir.resolve("package.json");
//
//        if (!Files.exists(packageJsonPath)) {
//            sb.append("No package.json found at root.\n\n");
//            return;
//        }
//
//        try {
//            ObjectMapper mapper = new ObjectMapper();
//            JsonNode root = mapper.readTree(packageJsonPath.toFile());
//
//            String projectName = root.path("name").asText("Unknown Project");
//            sb.append("- **Project Name:** ").append(projectName).append("\n");
//
//            JsonNode deps = root.path("dependencies");
//            List<String> notes = new ArrayList<>();
//
//            if (deps.has("@ngrx/store")) notes.add("NgRx (Redux pattern) used for State Management.");
//            if (deps.has("@ngxs/store")) notes.add("NGXS used for State Management.");
//            if (deps.has("@angular/material")) notes.add("Angular Material used for UI components.");
//            if (deps.has("rxjs")) notes.add("RxJS heavily utilized for reactive programming.");
//
//            if (checkStandaloneUsage(repoDir)) {
//                notes.add("Standalone Components are utilized (NgModules bypassed/optional).");
//            } else {
//                notes.add("Traditional NgModule architecture in use.");
//            }
//
//            for (String note : notes) {
//                sb.append("- ").append(note).append("\n");
//            }
//            sb.append("\n");
//
//        } catch (Exception e) {
//            sb.append("Error parsing package.json\n\n");
//        }
//    }
//
//    private boolean checkStandaloneUsage(Path rootDir) {
//        try (Stream<Path> paths = Files.walk(rootDir)) {
//            return paths.filter(Files::isRegularFile)
//                    .filter(p -> p.toString().endsWith(".ts"))
//                    .filter(p -> !p.toString().contains("node_modules"))
//                    .anyMatch(p -> {
//                        try {
//                            return Files.readString(p).contains("standalone: true");
//                        } catch (IOException e) { return false; }
//                    });
//        } catch (IOException e) { return false; }
//    }
//
//    private void appendDirectoryStructure(Path srcAppDir, StringBuilder sb) throws IOException {
//        sb.append("### Directory Structure\n```text\n");
//
//        Files.walkFileTree(srcAppDir, new SimpleFileVisitor<>() {
//            @Override
//            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
//                String name = dir.getFileName().toString();
//                if (name.startsWith(".") || name.equals("node_modules")) return FileVisitResult.SKIP_SUBTREE;
//
//                int depth = srcAppDir.relativize(dir).getNameCount();
//                if (dir.equals(srcAppDir)) depth = 0;
//                sb.append("  ".repeat(depth)).append("├── ").append(name).append("/\n");
//                return FileVisitResult.CONTINUE;
//            }
//
//            @Override
//            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
//                if (!file.toString().endsWith(".ts") && !file.toString().endsWith(".html")) return FileVisitResult.CONTINUE;
//                int depth = srcAppDir.relativize(file).getNameCount();
//                sb.append("  ".repeat(depth)).append("├── ").append(file.getFileName()).append("\n");
//                return FileVisitResult.CONTINUE;
//            }
//        });
//        sb.append("```\n\n");
//    }
//
//    private void appendImportantFiles(Path srcAppDir, StringBuilder sb) throws IOException {
//        sb.append("### Important Core Files\n\n");
//
//        List<Path> coreFiles = new ArrayList<>();
//        Files.walkFileTree(srcAppDir, new SimpleFileVisitor<>() {
//            @Override
//            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
//                String name = dir.getFileName().toString();
//                if (name.startsWith(".") || name.equals("node_modules")) return FileVisitResult.SKIP_SUBTREE;
//                return FileVisitResult.CONTINUE;
//            }
//
//            @Override
//            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
//                String name = file.getFileName().toString();
//                if (name.endsWith(".model.ts") || name.endsWith(".service.ts") ||
//                        name.endsWith("app.routes.ts") || name.endsWith("app.config.ts") ||
//                        name.endsWith(".module.ts")) {
//                    coreFiles.add(file);
//                }
//                return FileVisitResult.CONTINUE;
//            }
//        });
//
//        for (Path file : coreFiles) {
//            String relativePath = srcAppDir.getParent() != null ? srcAppDir.getParent().relativize(file).toString() : file.getFileName().toString();
//            sb.append("#### ").append(relativePath).append("\n```typescript\n");
//            sb.append(Files.readString(file)).append("\n```\n\n");
//        }
//    }
//
//    private String getGithubToken() {
//        Path standardPath = Paths.get(System.getProperty("user.home"), ".github_token");
//        if (Files.exists(standardPath)) {
//            try { return Files.readString(standardPath).trim(); } catch (Exception ignored) {}
//        }
//        return null;
//    }
//
//    private void deleteDirectoryRecursively(Path path) {
//        if (!Files.exists(path)) return;
//        try {
//            Files.walkFileTree(path, new SimpleFileVisitor<>() {
//                @Override
//                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
//                    Files.delete(file);
//                    return FileVisitResult.CONTINUE;
//                }
//                @Override
//                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
//                    Files.delete(dir);
//                    return FileVisitResult.CONTINUE;
//                }
//            });
//        } catch (IOException e) {
//            logger.warn("Failed to clean up directory: {}", path, e);
//        }
//    }
//}