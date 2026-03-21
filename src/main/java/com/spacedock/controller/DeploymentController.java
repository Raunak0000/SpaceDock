package com.spacedock.controller;


import com.spacedock.dto.DeployRequest;
import com.spacedock.service.GitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/deployments")
@CrossOrigin(origins =  "*")
public class DeploymentController {
        private final GitService gitService;

    // This is Dependency Injection. Spring Boot automatically hands the controller our GitService.
    public DeploymentController(GitService gitService) {
        this.gitService = gitService;
    }

    @PostMapping
    public ResponseEntity<String> triggerDeployment(@RequestBody DeployRequest request) {
        if (request.getRepoUrl() == null || request.getRepoUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Error: GitHub URL cannot be empty.");
        }

        UUID deploymentId = UUID.randomUUID();
        System.out.println("🚀 Deployment queued for: " + request.getRepoUrl());

        gitService.deploy(request.getRepoUrl(), deploymentId);

        return ResponseEntity.accepted().body("Deployment queued. ID: " + deploymentId);
    }
}
