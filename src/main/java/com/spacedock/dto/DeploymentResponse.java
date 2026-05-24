package com.spacedock.dto;

import com.spacedock.model.Deployment;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for deployment data returned by the API.
 * Deliberately EXCLUDES environment variables to prevent secret leakage.
 */
public class DeploymentResponse {
    private UUID id;
    private String repoUrl;
    private String commitHash;
    private String containerId;
    private Integer portNumber;
    private Deployment.DeploymentStatus status;
    private LocalDateTime createdAt;
    private int envVarCount; // Show count without exposing values

    public DeploymentResponse() {}

    /**
     * Converts a Deployment entity to a safe response DTO.
     */
    public static DeploymentResponse fromEntity(Deployment deployment) {
        DeploymentResponse dto = new DeploymentResponse();
        dto.setId(deployment.getId());
        dto.setRepoUrl(deployment.getRepoUrl());
        dto.setCommitHash(deployment.getCommitHash());
        dto.setContainerId(deployment.getContainerId());
        dto.setPortNumber(deployment.getPortNumber());
        dto.setStatus(deployment.getStatus());
        dto.setCreatedAt(deployment.getCreatedAt());
        dto.setEnvVarCount(
                deployment.getEnvironmentVariables() != null
                        ? deployment.getEnvironmentVariables().size()
                        : 0);
        return dto;
    }

    // --- Getters and Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public Integer getPortNumber() { return portNumber; }
    public void setPortNumber(Integer portNumber) { this.portNumber = portNumber; }

    public Deployment.DeploymentStatus getStatus() { return status; }
    public void setStatus(Deployment.DeploymentStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getEnvVarCount() { return envVarCount; }
    public void setEnvVarCount(int envVarCount) { this.envVarCount = envVarCount; }
}
