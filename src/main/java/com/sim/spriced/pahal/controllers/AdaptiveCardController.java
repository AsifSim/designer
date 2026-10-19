package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.AdaptiveCardService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/adaptive-card")
public class AdaptiveCardController {

    private final AdaptiveCardService adaptiveCardService;

    public AdaptiveCardController(AdaptiveCardService adaptiveCardService) {
        this.adaptiveCardService = adaptiveCardService;
    }

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> convertToAdaptiveCard(@RequestBody String sourceJson) {
        String adaptiveCardJson = adaptiveCardService.generateAdaptiveCard(sourceJson);
        return ResponseEntity.ok(adaptiveCardJson);
    }
}