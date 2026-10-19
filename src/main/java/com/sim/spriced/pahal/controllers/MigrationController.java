package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.TriggerMigrationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/migration")
@CrossOrigin(origins = "*")
public class MigrationController {

    private final TriggerMigrationService migrationService;

    public MigrationController(TriggerMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping("/projects")
    public ResponseEntity<List<String>> getAvailableProjects() {
        return ResponseEntity.ok(migrationService.listProjects());
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startMigration(
            @RequestBody Map<String, String> request) {

        String projectName = request.getOrDefault(
                "projectName",
                "spriced-client-cummins-parts-pricing");

        String branchName = request.getOrDefault(
                "branchName",
                "feat-rest-endpoints-migration");

        SseEmitter emitter = new SseEmitter(1800_000L);

        migrationService.runMigration(projectName, branchName, emitter);

        return emitter;
    }
}