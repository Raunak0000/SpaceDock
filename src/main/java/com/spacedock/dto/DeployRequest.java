package com.spacedock.dto;
import java.util.Map;

public class DeployRequest {
    private String repoUrl;
    private Map<String, String> envVars; // NEW FIELD

    public DeployRequest() {}

    public String getRepoUrl(){ return repoUrl; }
    public void setRepoUrl(String repoUrl){ this.repoUrl = repoUrl; }
    
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> envVars) { this.envVars = envVars; }
}