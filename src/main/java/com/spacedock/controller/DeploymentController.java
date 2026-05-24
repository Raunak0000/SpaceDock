package com.spacedock.controller;

import com.spacedock.dto.DeployRequest;
import com.spacedock.dto.DeploymentResponse;
import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.service.DockerService;
import com.spacedock.service.GitService;
import com.spacedock.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final GitService gitService;
    private final DeploymentRepository deploymentRepository;
    private final DockerService dockerService;
    private final CryptoUtil cryptoUtil;

    public DeploymentController(GitService gitService,
            DeploymentRepository deploymentRepository,
            DockerService dockerService,
            @Value("${spacedock.encryption-key}") String encryptionKey) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
        this.dockerService = dockerService;
        this.cryptoUtil = new CryptoUtil(encryptionKey);
    }

    @PostMapping
    public ResponseEntity<String> triggerDeployment(
            @RequestBody DeployRequest request) {
        if (request.getRepoUrl() == null
                || request.getRepoUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("Error: Repository URL cannot be empty.");
        }

        // ── SSRF Protection (fix #4) ──
        // Block dangerous URL schemes and internal network targets
        String repoUrl = request.getRepoUrl().trim();
        String urlLower = repoUrl.toLowerCase();

        if (!urlLower.startsWith("https://")) {
            return ResponseEntity.badRequest()
                    .body("Error: Only HTTPS repository URLs are allowed.");
        }

        // Block internal/private hostnames and IPs
        String hostPart = urlLower.replace("https://", "").split("/")[0].split(":")[0];
        if (hostPart.equals("localhost")
                || hostPart.equals("127.0.0.1")
                || hostPart.startsWith("192.168.")
                || hostPart.startsWith("10.")
                || hostPart.startsWith("172.16.") || hostPart.startsWith("172.17.")
                || hostPart.startsWith("172.18.") || hostPart.startsWith("172.19.")
                || hostPart.startsWith("172.2") || hostPart.startsWith("172.30.")
                || hostPart.startsWith("172.31.")
                || hostPart.equals("0.0.0.0")
                || hostPart.endsWith(".internal")
                || hostPart.endsWith(".local")) {
            return ResponseEntity.badRequest()
                    .body("Error: Internal/private URLs are not allowed.");
        }

        Deployment deployment = new Deployment();
        deployment.setRepoUrl(repoUrl);

        // Encrypt environment variable values before storing in the database
        if (request.getEnvVars() != null && !request.getEnvVars().isEmpty()) {
            Map<String, String> encrypted = new HashMap<>();
            for (Map.Entry<String, String> entry : request.getEnvVars().entrySet()) {
                encrypted.put(entry.getKey(), cryptoUtil.encrypt(entry.getValue()));
            }
            deployment.setEnvironmentVariables(encrypted);
        }

        Deployment saved = deploymentRepository.save(deployment);

        // Kick off the async deploy pipeline
        gitService.deploy(saved.getRepoUrl(), saved.getId());

        return ResponseEntity.ok("Deployment queued. ID: " + saved.getId());
    }

    @GetMapping
    public ResponseEntity<List<DeploymentResponse>> getAllDeployments() {
        List<DeploymentResponse> responses = deploymentRepository.findAll()
                .stream()
                .map(DeploymentResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeploymentResponse> getDeployment(@PathVariable UUID id) {
        return deploymentRepository.findById(id)
                .map(DeploymentResponse::fromEntity)
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