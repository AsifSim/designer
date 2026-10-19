package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.controllers.RepositorySetupService;
import com.sim.spriced.pahal.utility.ToolTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Component
public class PlatformWorkspaceTools {

    private static final Logger logger = LoggerFactory.getLogger(PlatformWorkspaceTools.class);
    private final RepositorySetupService repositorySetupService;

    public PlatformWorkspaceTools(RepositorySetupService repositorySetupService) {
        this.repositorySetupService = repositorySetupService;
    }

    @Tool(description = "Reads the .env file from the project root to check current environment configurations (like IB_THREADS, DB properties, or localhost status).")
    public String readEnvironmentVariables() {
        ToolTracker.track("readEnvironmentVariables", "Read environment variables from .env");
        try {
            Path envPath = Paths.get(System.getProperty("user.dir"), ".env");
            if (Files.exists(envPath)) {
                return Files.readAllLines(envPath).stream()
                        .filter(line -> !line.trim().isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.joining("\n"));
            }
            return "No .env file found in the root directory. Assume default/localhost environment.";
        } catch (Exception e) {
            logger.error("Error reading .env", e);
            return "Error reading .env file: " + e.getMessage();
        }
    }

    @Tool(description = "Clones the microservice repository and creates a new branch. Returns the physical path to the new submodule.")
    public String setupMicroserviceRepository(
            @ToolParam(description = "The base branch to clone from") String baseBranch,
            @ToolParam(description = "The new branch name to create") String newBranchName,
            @ToolParam(description = "The name of the service (used as artifactId and folder name)") String serviceName) {
        ToolTracker.track("setupMicroserviceRepository", "Set up microservice repo: " + serviceName);
        try {
            Path submodulePath = repositorySetupService.setupRepository(baseBranch, newBranchName, serviceName, null);
            return "Repository setup successful. Submodule path: " + submodulePath.toAbsolutePath().toString();
        } catch (Exception e) {
            logger.error("Repository setup failed", e);
            return "Failed to setup repository: " + e.getMessage();
        }
    }

    @Tool(description = "Saves the generated Java microservice code to the newly cloned repository workspace.")
    public String saveMicroserviceCode(
            @ToolParam(description = "The physical submodule path returned by setupMicroserviceRepository") String submodulePath,
            @ToolParam(description = "The exact name of the Java class/service") String serviceName,
            @ToolParam(description = "The complete generated Java source code") String code) {
        ToolTracker.track("saveMicroserviceCode", "Saved microservice code for: " + serviceName);
        try {
            Path targetDir = Paths.get(submodulePath, "src", "main", "java", "com", "sim", "spriced", "application", "service");
            Files.createDirectories(targetDir);

            String safeFileName = serviceName.contains("/") ? serviceName.substring(serviceName.lastIndexOf("/") + 1) : serviceName;
            Path filePath = targetDir.resolve(safeFileName + ".java");

            Files.writeString(filePath, code);
            return "Java code successfully saved to: " + filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            logger.error("Failed to save microservice code", e);
            return "Failed to save code: " + e.getMessage();
        }
    }
}