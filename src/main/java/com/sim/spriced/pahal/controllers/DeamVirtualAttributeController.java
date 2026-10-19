package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.DeamGeneratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/deam")
public class DeamVirtualAttributeController {

    private final DeamGeneratorService deamGeneratorService;

    public DeamVirtualAttributeController(DeamGeneratorService deamGeneratorService) {
        this.deamGeneratorService = deamGeneratorService;
    }

    @PostMapping("/generate-new")
    public ResponseEntity<Map<String, String>> generateNewDeam(@RequestParam("tableName") String tableName) {
        try {
            Map<String, String> result = deamGeneratorService.generateAndSaveNewDeam(tableName);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create new DEAM: " + e.getMessage()));
        }
    }
}