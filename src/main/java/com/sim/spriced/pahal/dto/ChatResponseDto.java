package com.sim.spriced.pahal.dto;

import java.util.List;
import java.util.Map;

public class ChatResponseDto {
    private String message;
    private List<Map<String, String>> toolsUsed;

    public ChatResponseDto(String message, List<Map<String, String>> toolsUsed) {
        this.message = message;
        this.toolsUsed = toolsUsed;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<Map<String, String>> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<Map<String, String>> toolsUsed) { this.toolsUsed = toolsUsed; }
}