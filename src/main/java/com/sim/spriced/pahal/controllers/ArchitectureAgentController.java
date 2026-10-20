package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.dto.ChatResponseDto;
import com.sim.spriced.pahal.services.PlatformGenerationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/v1/architecture")
public class ArchitectureAgentController {

    private final PlatformGenerationService generationService;

    public ArchitectureAgentController(PlatformGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponseDto> chat(
            @RequestParam("message") String message,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        ChatResponseDto response = generationService.chatWithAgent(message, sessionId, files);
        return ResponseEntity.ok(response);
    }

//    @GetMapping("/download/{fileName}")
//    public ResponseEntity<Resource> downloadExcel(@PathVariable String fileName) {
//        try {
//            Path filePath = Paths.get(System.getProperty("user.dir"), "generated_excels", fileName).normalize();
//            Resource resource = new UrlResource(filePath.toUri());
//
//            if (resource.exists() || resource.isReadable()) {
//                return ResponseEntity.ok()
//                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
//                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
//                        .body(resource);
//            }
//            return ResponseEntity.notFound().build();
//        } catch (Exception e) {
//            return ResponseEntity.internalServerError().build();
//        }
//    }

    // CRITICAL FIX: The :.+ prevents Spring from stripping the .xlsx extension
    @GetMapping("/download/{fileName:.+}")
    public ResponseEntity<Resource> downloadExcel(@PathVariable("fileName") String fileName) {
        try {
            // Safely resolve the absolute file path
            Path dirPath = Paths.get(System.getProperty("user.dir"), "generated_excels").toAbsolutePath().normalize();
            Path filePath = dirPath.resolve(fileName).normalize();

            // Security Check: Prevent directory traversal attacks
            if (!filePath.startsWith(dirPath)) {
                return ResponseEntity.status(403).build();
            }

            File file = filePath.toFile();

            if (!file.exists() || !file.canRead()) {
                return ResponseEntity.notFound().build();
            }

            // CRITICAL FIX: Use FileSystemResource instead of UrlResource for safer local file reading
            Resource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}