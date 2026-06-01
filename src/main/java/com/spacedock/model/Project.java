package com.spacedock.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Unique per repository so we can map incoming builds or webhooks to a stable
    // project config
    @Column(nullable = false, unique = true)
    private String repoUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_global_secrets", joinColumns = @JoinColumn(name = "project_id"))
    @MapKeyColumn(name = "secret_key")
    @Column(name = "secret_value", length = 1024)
    private Map<String, String> globalSecrets;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public Map<String, String> getGlobalSecrets() {
        return globalSecrets;
    }

    public void setGlobalSecrets(Map<String, String> globalSecrets) {
        this.globalSecrets = globalSecrets;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}