package com.sim.spriced.pahal.dto;

import java.util.List;

public class ArchitecturePlanResponse {
    private int wfmRequired;
    private int ttmRequired;
    private int deamRequired;
    private int microservicesRequired;
    private List<String> architecturalDecisions;

    // Holds the LLM's conversational text (e.g., "Do you want to proceed?")
    private String agentMessage;

    // Holds the final generated JSON structures in Phase 2
    private String generatedDeamJson;
    private String generatedWfmJson;

    // --- Original Getters and Setters ---
    public int getWfmRequired() { return wfmRequired; }
    public void setWfmRequired(int wfmRequired) { this.wfmRequired = wfmRequired; }

    public int getTtmRequired() { return ttmRequired; }
    public void setTtmRequired(int ttmRequired) { this.ttmRequired = ttmRequired; }

    public int getDeamRequired() { return deamRequired; }
    public void setDeamRequired(int deamRequired) { this.deamRequired = deamRequired; }

    public int getMicroservicesRequired() { return microservicesRequired; }
    public void setMicroservicesRequired(int microservicesRequired) { this.microservicesRequired = microservicesRequired; }

    public List<String> getArchitecturalDecisions() { return architecturalDecisions; }
    public void setArchitecturalDecisions(List<String> architecturalDecisions) { this.architecturalDecisions = architecturalDecisions; }

    // --- New Getters and Setters ---
    public String getAgentMessage() { return agentMessage; }
    public void setAgentMessage(String agentMessage) { this.agentMessage = agentMessage; }

    public String getGeneratedDeamJson() { return generatedDeamJson; }
    public void setGeneratedDeamJson(String generatedDeamJson) { this.generatedDeamJson = generatedDeamJson; }

    public String getGeneratedWfmJson() { return generatedWfmJson; }
    public void setGeneratedWfmJson(String generatedWfmJson) { this.generatedWfmJson = generatedWfmJson; }
}