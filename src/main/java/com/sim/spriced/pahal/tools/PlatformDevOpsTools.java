package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.utility.ToolTracker;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class PlatformDevOpsTools {

    private static final Logger logger = LoggerFactory.getLogger(PlatformDevOpsTools.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String REPO_URL = "https://github.com/simaiserver/spriced-application-2.0.101.git";

    private final DevOpsTools bashTools;

    public PlatformDevOpsTools(DevOpsTools bashTools) {
        this.bashTools = bashTools;
    }

    @Tool(description = "Clones the microservice repository, checks out a base branch, creates a new branch, and sets up the Maven module.")
    public String setupMicroserviceRepository(
            @ToolParam(description = "The base branch to clone from (e.g. dev-baseline)") String baseBranch,
            @ToolParam(description = "The new branch name to create") String newBranchName,
            @ToolParam(description = "The service name for the new module") String serviceName) {
        ToolTracker.track("setupMicroserviceRepository", "Set up microservice repo: " + serviceName);
        try {
            logger.info("Initializing repository setup for service: {}", serviceName);

            String token = getGithubToken();
            if (token == null || token.isEmpty()) {
                return "Error: GitHub token not found at ~/.github_token";
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String targetFolderName = newBranchName + "_" + timestamp;

            Path workspacePath = Path.of(System.getProperty("user.dir"), "cloned_repositories", targetFolderName);
            File localRepoDir = workspacePath.toFile();

            logger.info("Cloning repository branch: {}", baseBranch);
            UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(token, "");

            try (Git git = Git.cloneRepository()
                    .setURI(REPO_URL)
                    .setDirectory(localRepoDir)
                    .setCredentialsProvider(credentials)
                    .setCloneAllBranches(true)
                    .setBranch(baseBranch)
                    .call()) {

                logger.info("Creating and checking out new branch: {}", newBranchName);
                git.branchCreate().setName(newBranchName).call();
                git.checkout().setName(newBranchName).call();

                // Create Maven submodule directory tree
                logger.info("Building module directory structure...");
                Path submodulePath = workspacePath.resolve(Path.of("data-entity-services", serviceName));
                Path packageDirPath = submodulePath.resolve(Path.of("src", "main", "java", "com", "sim", "spriced", "application", "service"));
                Path resourceDirPath = submodulePath.resolve(Path.of("src", "main", "resources"));

                Files.createDirectories(packageDirPath);
                Files.createDirectories(resourceDirPath);

                // Write pom.xml
                String pomContent = getPomTemplate(serviceName);
                Files.writeString(submodulePath.resolve("pom.xml"), pomContent);

                // Write application.properties
                String propertiesContent = "spring.application.name=" + serviceName + "\n";
                Files.writeString(resourceDirPath.resolve("application.properties"), propertiesContent);

                logger.info("Repository setup finished successfully at {}", submodulePath);
                return "Repository successfully cloned and configured at: " + submodulePath.toString();
            }
        } catch (Exception e) {
            logger.error("Failed to setup repository", e);
            return "Error setting up repository: " + e.getMessage();
        }
    }

    @Tool(description = "Reads the .env file from the deployed platform workspace to check properties like IB_THREADS, DB configs, etc.")
    public String readEnvironmentProperties(@ToolParam(description = "Target host alias or 'localhost'") String vmHost) {
        ToolTracker.track("readEnvironmentProperties", "Read environment properties from: " + vmHost);
        String command = "cat $HOME/deployments/spriced-env/app/.env || echo 'No .env file found.'";
        return bashTools.executeCustomCommand(vmHost, command);
    }

    @Tool(description = "Saves the generated Java microservice code directly to the cloned repository workspace.")
    public String saveGeneratedJavaCode(
            @ToolParam(description = "The absolute path to the submodule directory (returned by setupMicroserviceRepository tool)") String submodulePath,
            @ToolParam(description = "The name of the service/Java class") String serviceName,
            @ToolParam(description = "The generated Java code") String code) {
        ToolTracker.track("saveGeneratedJavaCode", "Saved generated Java code for: " + serviceName);
        try {
            Path targetDir = Path.of(submodulePath, "src", "main", "java", "com", "sim", "spriced", "application", "service");
            Files.createDirectories(targetDir);

            // Clean up class name to prevent path traversal issues
            String safeFileName = serviceName.contains("/") ? serviceName.substring(serviceName.lastIndexOf("/") + 1) : serviceName;

            Path filePath = targetDir.resolve(safeFileName + ".java");
            Files.writeString(filePath, code);
            return "Code saved successfully to " + filePath.toString();
        } catch (Exception e) {
            return "Failed to save code: " + e.getMessage();
        }
    }

    private String getGithubToken() {
        Path standardPath = Paths.get(System.getProperty("user.home"), ".github_token");
        Path tokenFile = Files.exists(standardPath) ? standardPath :
                (IS_WINDOWS ? Paths.get("\\\\wsl$\\Ubuntu\\home\\asif\\.github_token") : null);

        if (tokenFile != null && Files.exists(tokenFile)) {
            try { return Files.readString(tokenFile).trim(); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private String getPomTemplate(String artifactName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n\n" +
                "    <modelVersion>4.0.0</modelVersion>\n\n" +
                "    <parent>\n" +
                "        <groupId>com.sim.spriced.application</groupId>\n" +
                "        <artifactId>data-entity-services</artifactId>\n" +
                "        <version>2.0.101-SNAPSHOT</version>\n" +
                "        <relativePath>../pom.xml</relativePath>\n" +
                "    </parent>\n\n" +
                "    <groupId>com.sim.spriced.application</groupId>\n" +
                "    <artifactId>" + artifactName + "</artifactId>\n" +
                "    <version>3.0.0-SNAPSHOT</version>\n\n" +
                "    <name>" + artifactName + "</name>\n" +
                "    <description>" + artifactName + "</description>\n\n" +
                "    <properties>\n" +
                "        <java.version>21</java.version>\n" +
                "    </properties>\n\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.springframework.boot</groupId>\n" +
                "            <artifactId>spring-boot-starter-test</artifactId>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.springframework.boot</groupId>\n" +
                "                <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "                <executions>\n" +
                "                    <execution>\n" +
                "                        <id>repackage</id>\n" +
                "                        <phase>none</phase>\n" +
                "                    </execution>\n" +
                "                </executions>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "</project>";
    }
}