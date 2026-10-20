package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.AngularMigrationService;
import com.sim.spriced.pahal.services.AngularBulkMigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/migration")
public class AngularMigrationController {

    private final AngularMigrationService migrationService;
    private final AngularBulkMigrationService bulkMigrationService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AngularMigrationController(AngularMigrationService migrationService, AngularBulkMigrationService bulkMigrationService) {
        this.migrationService = migrationService;
        this.bulkMigrationService = bulkMigrationService;
    }

    @GetMapping("/projects")
    public ResponseEntity<List<String>> getAvailableProjects() {
        return ResponseEntity.ok(migrationService.getAvailableProjects());
    }

    // Standard Chat Endpoint
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chatWithMigrationAgent(
            @RequestParam("oldProject") String oldProject,
            @RequestParam("newProject") String newProject,
            @RequestParam("message") String message,
            @RequestParam("sessionId") String sessionId) {
        try {
            String aiResponse = migrationService.processMigrationChat(oldProject, newProject, message, sessionId);
            return ResponseEntity.ok(Map.of("message", aiResponse));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // New Bulk Migration Streaming Endpoint
    @GetMapping(value = "/bulk-start", produces = "text/event-stream")
    public SseEmitter startBulkMigration(
            @RequestParam("oldProject") String oldProject,
            @RequestParam("newProject") String newProject) {

        // Use 0L or -1L to disable the timeout. This ensures the connection
        // stays alive for the entire duration of the bulk migration loop.
        SseEmitter emitter = new SseEmitter(0L);

        executor.execute(() -> bulkMigrationService.executeBulkMigration(oldProject, newProject, emitter));

        return emitter;
    }
}