package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.utility.ToolTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PlatformDocsTool {

    private static final Logger logger = LoggerFactory.getLogger(PlatformDocsTool.class);
    private final RestClient restClient = RestClient.create();

    @Tool(description = "Fetches the latest architectural README documentation from GitHub for a specific platform module (DML or IBOB). Use this tool whenever you need design generation guidelines.")
    public String fetchPlatformDocumentation(@ToolParam(description = "Module name: exactly 'dml' or 'ib-ob'") String module) {

        ToolTracker.track("fetchPlatformDocumentation", "Fetched GitHub design guidelines for: " + module);

        String url = module.equalsIgnoreCase("dml")
                ? "https://api.github.com/repos/simaiserver/spriced-platform-data-management-layer/contents/README.md?ref=dev-baseline"
                : "https://api.github.com/repos/simaiserver/spriced-platform-ib-ob/contents/README.md?ref=dev-baseline";

        try {
            String token = getGithubToken();
            return restClient.get().uri(url)
                    .header("Accept", "application/vnd.github.v3.raw")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            logger.error("Failed to fetch docs", e);
            return "Failed to fetch documentation.";
        }
    }

    private String getGithubToken() {
        Path standardPath = Paths.get(System.getProperty("user.home"), ".github_token");
        if (Files.exists(standardPath)) {
            try { return Files.readString(standardPath).trim(); } catch (Exception ignored) {}
        }
        return null;
    }
}