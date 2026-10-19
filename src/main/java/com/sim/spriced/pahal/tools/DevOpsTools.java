package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.utility.ToolTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class DevOpsTools {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsTools.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @Tool(description = "Scans ~/.ssh/config and returns available deployment targets including 'localhost'.")
    public List<String> getAvailableVms() {
        ToolTracker.track("getAvailableVms", "Scanned SSH config for available VMs");
        List<String> targets = new ArrayList<>();
        targets.add("localhost (local machine)");

        Path sshConfig = getSshConfigPath();

        if (sshConfig != null && Files.exists(sshConfig)) {
            try {
                List<String> vms = Files.readAllLines(sshConfig).stream()
                        .map(String::trim)
                        .filter(line -> line.toLowerCase().startsWith("host ") && !line.contains("*"))
                        .map(line -> line.substring(5).trim())
                        .toList();
                targets.addAll(vms);
                logger.info("Successfully loaded {} VMs from config at {}.", vms.size(), sshConfig);
            } catch (Exception e) {
                logger.warn("Failed to read SSH config from path: {}", sshConfig, e);
            }
        } else {
            logger.error("WARNING: SSH config file NOT FOUND. Localhost will be the only option.");
        }
        return targets;
    }

    private Path getSshConfigPath() {
        Path standardPath = Paths.get(System.getProperty("user.home"), ".ssh", "config");
        if (Files.exists(standardPath)) return standardPath;

        if (IS_WINDOWS) {
            Path wslPath = Paths.get("\\\\wsl$\\Ubuntu\\home\\asif\\.ssh\\config");
            if (Files.exists(wslPath)) return wslPath;
        }
        return null;
    }

    @Tool(description = "Clean installs Platform 2.0 components (dml-tml and ib-ob) on the target host (local or remote VM), ensuring Java 21 is installed and active.")
    public String deployPlatform(@ToolParam(description = "Target host alias or 'localhost'") String vmHost) {
        ToolTracker.track("deployPlatform", "Deployed Platform 2.0 components on: " + vmHost);
        String script = """
        set -e
        WORKSPACE_DIR="$HOME/deployments/spriced-env"
        TOKEN_FILE="$HOME/.github_token"
        
        echo "=== Starting Platform 2.0 Deployment ==="
        
        if [ ! -f "$TOKEN_FILE" ]; then
            echo "ERROR: GitHub token config not found at $TOKEN_FILE"
            echo "Please create it by running: echo 'your_token' > $TOKEN_FILE"
            exit 1
        fi
        
        # Read the token and remove any hidden newlines
        GIT_TOKEN=$(cat "$TOKEN_FILE" | tr -d '\\r\\n')
        
        # Force JAVA_HOME and PATH to use Java 21 for this Maven build session
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
        
        echo "Setting up workspace at: $WORKSPACE_DIR"
        mkdir -p "$WORKSPACE_DIR"
        cd "$WORKSPACE_DIR" || { echo "Failed to navigate to workspace"; exit 1; }
        
        echo "\\n---> [1/2] Processing Data Management Layer (dml-tml)..."
        if [ ! -d "dml-tml" ]; then
            echo "Status: Cloning fresh repo for dml-tml (branch: dev-baseline)..."
            git clone -b dev-baseline "https://${GIT_TOKEN}@github.com/simaiserver/spriced-platform-data-management-layer.git" dml-tml
        else
            echo "Status: Directory exists. Pulling latest dev-baseline for dml-tml..."
            # Authenticate remote URL temporarily for the pull
            cd dml-tml && git pull "https://${GIT_TOKEN}@github.com/simaiserver/spriced-platform-data-management-layer.git" dev-baseline && cd ..
        fi
        echo "Status: Building dml-tml..."
        cd dml-tml && mvn clean install -DskipTests
        cd ..
        
        echo "\\n---> [2/2] Processing IB-OB..."
        if [ ! -d "ib-ob" ]; then
            echo "Status: Cloning fresh repo for ib-ob (branch: dev-baseline)..."
            git clone -b dev-baseline "https://${GIT_TOKEN}@github.com/simaiserver/spriced-platform-ib-ob.git" ib-ob
        else
            echo "Status: Directory exists. Pulling latest dev-baseline for ib-ob..."
            cd ib-ob && git pull "https://${GIT_TOKEN}@github.com/simaiserver/spriced-platform-ib-ob.git" dev-baseline && cd ..
        fi
        echo "Status: Building ib-ob..."
        cd ib-ob && mvn clean install -DskipTests
        
        echo "\\n=== Platform 2.0 Deployment Completed ==="
        """;

        return runCommandOnTarget(vmHost, script);
    }

    @Tool(description = "Configures DB in .env, builds, and deploys the application container on the target host.")
    public String deployApplication(
            @ToolParam(description = "Target host alias or 'localhost'") String vmHost,
            @ToolParam(description = "The Git branch to deploy") String branchName,
            @ToolParam(description = "The Database connection URL") String dbConnection) {
        ToolTracker.track("deployApplication", "Deployed application on: " + vmHost + " branch: " + branchName);
        String script = """
        set -e
        WORKSPACE_DIR="$HOME/deployments/spriced-env"
        TOKEN_FILE="$HOME/.github_token"
        
        echo "=== Starting Application Deployment ==="
        
        if [ ! -f "$TOKEN_FILE" ]; then
            echo "ERROR: GitHub token config not found at $TOKEN_FILE"
            exit 1
        fi
        GIT_TOKEN=$(cat "$TOKEN_FILE" | tr -d '\\r\\n')
        
        # Force JAVA_HOME and PATH to use Java 21 for this Maven build session
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
        
        echo "Navigating to workspace at: $WORKSPACE_DIR"
        mkdir -p "$WORKSPACE_DIR"
        cd "$WORKSPACE_DIR" || { echo "Failed to navigate to workspace"; exit 1; }
        
        echo "\\n---> Processing Application Repository..."
        if [ ! -d "app/.git" ]; then
            echo "Status: Cloning fresh repo..."
            rm -rf app || true
            git clone -b %s "https://${GIT_TOKEN}@github.com/simaiserver/spriced-application-2.0.101.git" app
        else
            echo "Status: Directory exists. Pulling latest branch: %s..."
            cd app && git pull "https://${GIT_TOKEN}@github.com/simaiserver/spriced-application-2.0.101.git" %s && cd ..
        fi
        
        cd app
        echo "Status: Configuring .env file with provided Database URL..."
        echo "DB_URL=%s" > .env
        ENV_FILE="$(pwd)/.env"
        
        echo "Status: Locating Maven project directory..."
        PROJECT_DIR=$(find . -maxdepth 2 -name "pom.xml" -printf '%%h\\n' | head -n 1)
        if [ -z "$PROJECT_DIR" ]; then
            echo "ERROR: No pom.xml found in the application repository."
            exit 1
        fi
        cd "$PROJECT_DIR"
        echo "Status: Maven project directory: $PROJECT_DIR"
        
        echo "Status: Running Maven clean install..."
        mvn clean install -DskipTests
        
        echo "Status: Building Docker Image..."
        docker build -t spriced-app:latest .
        
        echo "Status: Managing Docker Containers..."
        docker stop spriced-app-container || true
        docker rm spriced-app-container || true
        docker run -d --name spriced-app-container --env-file "$ENV_FILE" -p 8080:8080 spriced-app:latest
        
        echo "\\n=== Application Deployment Completed ==="
        """.formatted(branchName, branchName, branchName, branchName, dbConnection);

        return runCommandOnTarget(vmHost, script);
    }

    @Tool(description = "Monitors the health of the deployed application container.")
    public String monitorHealth(@ToolParam(description = "Target host alias or 'localhost'") String vmHost) {
        ToolTracker.track("monitorHealth", "Monitored health on: " + vmHost);
        String script = """
        set -e
        curl -s -f http://localhost:8080/actuator/health || { echo "Application unreachable"; exit 1; }
        """;
        return runCommandOnTarget(vmHost, script);
    }

    @Tool(description = "Executes arbitrary custom Linux/Ubuntu shell commands on the target host. Use this to check system status, view logs, or install prerequisites (like Maven) if a build fails.")
    public String executeCustomCommand(
            @ToolParam(description = "Target host alias or 'localhost'") String vmHost,
            @ToolParam(description = "The shell command to execute") String command) {
        ToolTracker.track("executeCustomCommand", "Executed custom command on: " + vmHost);
        String script = """
        set -e
        echo "=== Executing Custom Command ==="
        echo "Command: %s"
        %s
        """.formatted(command, command);

        return runCommandOnTarget(vmHost, script);
    }

    private String runCommandOnTarget(String target, String script) {
        boolean isLocal = target == null
                || target.toLowerCase().contains("local")
                || target.equalsIgnoreCase("127.0.0.1");

        logger.info("Routing command to target: {}", isLocal ? "localhost" : target);

        if (isLocal) {
            return executeLocalCommand(script);
        } else {
            String remoteCommand = "ssh " + target + " 'bash -s' << 'EOF'\n" + script + "\nEOF\n";
            return executeLocalCommand(remoteCommand);
        }
    }

    private String executeLocalCommand(String command) {
        logger.debug("Executing shell command via temp script...");
        try {
            Path tempScript = Files.createTempFile("agent-deploy-", ".sh");
            String normalizedCommand = command.replace("\r\n", "\n");
            Files.writeString(tempScript, normalizedCommand);

            List<String> processCmd = new ArrayList<>();
            if (IS_WINDOWS) {
                processCmd.add("wsl");
                processCmd.add("bash");
                String pathStr = tempScript.toAbsolutePath().toString();
                String drive = pathStr.substring(0, 1).toLowerCase();
                String wslPath = "/mnt/" + drive + pathStr.substring(2).replace("\\", "/");
                processCmd.add(wslPath);
            } else {
                processCmd.add("bash");
                processCmd.add(tempScript.toAbsolutePath().toString());
            }

            ProcessBuilder builder = new ProcessBuilder(processCmd);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            // Real-time logging
            StringBuilder fullOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[Bash] {}", line);
                    fullOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            Files.deleteIfExists(tempScript);

            String completeLog = fullOutput.toString();

            if (exitCode == 0) {
                logger.info("Command executed successfully (Exit Code 0).");
                return "Success: The script executed and completed without errors.";
            } else {
                logger.error("Command failed with Exit Code {}.", exitCode);

                // Truncate the error sent to the AI to only the last 3000 characters
                int maxLength = 3000;
                String truncatedError = completeLog.length() > maxLength
                        ? "...(logs truncated to save space)...\n" + completeLog.substring(completeLog.length() - maxLength)
                        : completeLog;

                return "Failed with Exit Code " + exitCode + ". Here is the end of the error log:\n" + truncatedError;
            }
        } catch (Exception e) {
            logger.error("Execution Error while running bash command", e);
            return "Execution Error: " + e.getMessage();
        }
    }
}