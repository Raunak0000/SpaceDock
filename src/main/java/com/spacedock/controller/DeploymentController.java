package com.spacedock.controller;

import com.spacedock.dto.DeployRequest;
import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
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

    public DeploymentController(GitService gitService,
            DeploymentRepository deploymentRepository) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
    }

    @PostMapping("/prepare")
    public ResponseEntity<String> prepareDeployment(@RequestBody DeployRequest request) {
        if (request.getRepoUrl() == null || request.getRepoUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Error: GitHub URL cannot be empty.");
        }

        // Create the DB record FIRST
        Deployment deployment = new Deployment();
        deployment.setRepoUrl(request.getRepoUrl());
        Deployment saved = deploymentRepository.save(deployment);

        System.out.println("🚀 Deployment prepared for: " + request.getRepoUrl());
        System.out.println("   DB record created with ID: " + saved.getId());

        return ResponseEntity.ok(saved.getId().toString());
    }

    @PostMapping("/start/{id}")
    public ResponseEntity<String> startDeployment(@PathVariable UUID id) {
        return deploymentRepository.findById(id).map(d -> {
            gitService.deploy(d.getRepoUrl(), d.getId());
            return ResponseEntity.ok("Deployment started");
        }).orElse(ResponseEntity.notFound().build());
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
}