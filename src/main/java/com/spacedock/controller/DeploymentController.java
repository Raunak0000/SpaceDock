package com.spacedock.controller;

import com.spacedock.dto.DeployRequest;
import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.service.DockerService;
import com.spacedock.service.GitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/deployments")
@CrossOrigin(origins = "*")
public class DeploymentController {

    private final GitService gitService;
    private final DeploymentRepository deploymentRepository;
    private final DockerService dockerService;

    public DeploymentController(GitService gitService,
            DeploymentRepository deploymentRepository,
            DockerService dockerService) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
        this.dockerService = dockerService;
    }

    @PostMapping
    public ResponseEntity<String> triggerDeployment(
            @RequestBody DeployRequest request) {
        if (request.getRepoUrl() == null
                || request.getRepoUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("Error: GitHub URL cannot be empty.");
        }

        Deployment deployment = new Deployment();
        deployment.setRepoUrl(request.getRepoUrl());
        Deployment saved = deploymentRepository.save(deployment);

        System.out.println("🚀 Deployment queued for: " + request.getRepoUrl());
        System.out.println("   DB record created with ID: " + saved.getId());

        gitService.deploy(request.getRepoUrl(), saved.getId());

        return ResponseEntity.accepted()
                .body("Deployment queued. ID: " + saved.getId());
    }

    @GetMapping
    public ResponseEntity<List<Deployment>> getAllDeployments() {
        return ResponseEntity.ok(deploymentRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Deployment> getDeployment(@PathVariable UUID id) {
        return deploymentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Phase 4 — stop a running deployment
    @DeleteMapping("/{id}")
    public ResponseEntity<String> stopDeployment(@PathVariable UUID id) {
        return deploymentRepository.findById(id).map(deployment -> {
            if (deployment.getContainerId() != null) {
                dockerService.stopContainer(deployment.getContainerId());
            }
            deployment.setStatus(Deployment.DeploymentStatus.STOPPED);
            deploymentRepository.save(deployment);
            return ResponseEntity.ok("Deployment stopped: " + id);
        }).orElse(ResponseEntity.notFound().build());
    }
}