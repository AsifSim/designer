package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.AngularContextService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/angular-context")
public class AngularContextController {

    private final AngularContextService contextService;
    private static final Path LOCAL_PROJECT_PATH = Paths.get("C:/Users/asif.azmi_simadvisor/Desktop/Projects/spriced-ui");

    public AngularContextController(AngularContextService contextService) {
        this.contextService = contextService;
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generateContext(@RequestBody Map<String, String> payload) {
        String repoUrl = payload.get("repoUrl");
        String branchName = payload.get("branchName");

        // Allow repoUrl to be empty if the local folder exists on disk
        if ((repoUrl == null || repoUrl.trim().isEmpty()) && !Files.exists(LOCAL_PROJECT_PATH)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Repository URL is required because local path was not found."));
        }

        try {
            String fileName = contextService.generateLlmsTxt(repoUrl, branchName != null ? branchName : "main");
            String downloadUrl = "/api/v1/angular-context/download/" + fileName;
            return ResponseEntity.ok(Map.of("fileName", fileName, "downloadUrl", downloadUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            Path filePath = Paths.get(System.getProperty("user.dir"), "generated_contexts", fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() || resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"llms.txt\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}