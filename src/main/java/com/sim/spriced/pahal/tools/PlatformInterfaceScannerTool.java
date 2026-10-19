package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.utility.ToolTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class PlatformInterfaceScannerTool {

    private static final Logger logger = LoggerFactory.getLogger(PlatformInterfaceScannerTool.class);
    private final RestClient restClient;

    private static final String REPO_OWNER = "simaiserver";
    private static final String REPO_NAME = "spriced-platform-data-management-layer";
    private static final String BRANCH = "dev-baseline";
    private static final String BASE_PATH = "BusinessInterface/src/main/java/com/sim/spriced/platform/BusinessInterface";

    public PlatformInterfaceScannerTool() {
        // Configure the RestClient specifically for the GitHub API
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Tool(description = "Scans the Platform 2.0 repository for Java interface details. Pass an empty string to list all interfaces, or pass a specific interface name (e.g., 'MyInterface.java') to get its source code.")
    public String getPlatformInterfaceDetails(
            @ToolParam(description = "The name of the Java interface file to fetch, or an empty string to list all available interfaces.") String interfaceName) {

        ToolTracker.track("getPlatformInterfaceDetails", "Fetched platform interface details for: " + interfaceName);
        String token = getGithubToken();
        if (token == null || token.isEmpty()) {
            return "Error: GitHub token not found on the system at ~/.github_token";
        }

        try {
            if (interfaceName == null || interfaceName.trim().isEmpty()) {
                return listInterfaces(token);
            } else {
                return getInterfaceSourceCode(token, interfaceName);
            }
        } catch (Exception e) {
            logger.error("GitHub API request failed", e);
            return "Error fetching data from GitHub: " + e.getMessage();
        }
    }

    private String listInterfaces(String token) {
        String uri = String.format("/repos/%s/%s/contents/%s?ref=%s", REPO_OWNER, REPO_NAME, BASE_PATH, BRANCH);

        List<Map<String, Object>> contents = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (contents == null || contents.isEmpty()) {
            return "No files found in the specified directory.";
        }

        StringBuilder sb = new StringBuilder("Available Interfaces in Platform 2.0:\n");
        for (Map<String, Object> item : contents) {
            if ("file".equals(item.get("type")) && String.valueOf(item.get("name")).endsWith(".java")) {
                sb.append("- ").append(item.get("name")).append("\n");
            }
        }
        sb.append("\nTo view the code of an interface, call this tool again with the specific file name.");
        return sb.toString();
    }

    private String getInterfaceSourceCode(String token, String fileName) {
        String path = BASE_PATH + "/" + fileName;
        String uri = String.format("/repos/%s/%s/contents/%s?ref=%s", REPO_OWNER, REPO_NAME, path, BRANCH);

        Map<String, Object> fileData = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (fileData != null && fileData.containsKey("content")) {
            String base64Content = (String) fileData.get("content");
            // GitHub API returns Base64 encoded content broken into lines with \n
            base64Content = base64Content.replaceAll("\\r|\\n", "");
            byte[] decodedBytes = Base64.getDecoder().decode(base64Content);
            return new String(decodedBytes);
        }
        return "Content not found for file: " + fileName;
    }

    private String getGithubToken() {
        Path tokenFile = Paths.get(System.getProperty("user.home"), ".github_token");
        if (Files.exists(tokenFile)) {
            try {
                return Files.readString(tokenFile).trim();
            } catch (Exception e) {
                logger.warn("Failed to read GitHub token", e);
            }
        }
        return null;
    }
}