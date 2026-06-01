package com.spacedock.controller;

import com.spacedock.dto.DeployRequest;
import com.spacedock.dto.DeploymentResponse;
import com.spacedock.model.Deployment;
import com.spacedock.model.Project;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.repository.ProjectRepository;
import com.spacedock.service.DockerService;
import com.spacedock.service.GitService;
import com.spacedock.service.ProxyService;
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
    private final ProxyService proxyService;
    private final ProjectRepository projectRepository;

    public DeploymentController(GitService gitService,
            DeploymentRepository deploymentRepository,
            DockerService dockerService,
            ProxyService proxyService,
            ProjectRepository projectRepository,
            @Value("${spacedock.encryption-key}") String encryptionKey) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
        this.dockerService = dockerService;
        this.proxyService = proxyService;
        this.projectRepository = projectRepository;
        this.cryptoUtil = new CryptoUtil(encryptionKey);
    }

    @PostMapping
    public ResponseEntity<String> triggerDeployment(@RequestBody DeployRequest request) {
        if (request.getRepoUrl() == null || request.getRepoUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Error: Repository URL cannot be empty.");
        }

        String repoUrl = request.getRepoUrl().trim();
        String urlLower = repoUrl.toLowerCase();

        if (!urlLower.startsWith("https://")) {
            return ResponseEntity.badRequest().body("Error: Only HTTPS repository URLs are allowed.");
        }

        String hostPart = urlLower.replace("https://", "").split("/")[0].split(":")[0];
        if (hostPart.equals("localhost") || hostPart.equals("127.0.0.1") || hostPart.startsWith("192.168.")
                || hostPart.startsWith("10.") || hostPart.startsWith("172.16.") || hostPart.startsWith("172.17.")
                || hostPart.startsWith("172.18.") || hostPart.startsWith("172.19.") || hostPart.startsWith("172.2")
                || hostPart.startsWith("172.30.") || hostPart.startsWith("172.31.") || hostPart.equals("0.0.0.0")
                || hostPart.endsWith(".internal") || hostPart.endsWith(".local")) {
            return ResponseEntity.badRequest().body("Error: Internal/private URLs are not allowed.");
        }

        // 🟢 1. Handle Project Secrets Management Layer
        // Check if a workspace config context profile already exists for this
        // repository URL
        Map<String, String> projectSecrets = projectRepository.findByRepoUrl(repoUrl)
                .map(Project::getGlobalSecrets)
                .orElseGet(HashMap::new);

        // Process incoming user environment configuration variables from the request
        // payload
        Map<String, String> incomingEncryptedVars = new HashMap<>();
        if (request.getEnvVars() != null && !request.getEnvVars().isEmpty()) {
            for (Map.Entry<String, String> entry : request.getEnvVars().entrySet()) {
                incomingEncryptedVars.put(entry.getKey(), cryptoUtil.encrypt(entry.getValue()));
            }

            // Persist/Update the configuration parameters into the Project profiles table
            // for future iterations
            final Map<String, String> secretsToSave = new HashMap<>(incomingEncryptedVars);
            Project project = projectRepository.findByRepoUrl(repoUrl).orElseGet(() -> {
                Project p = new Project();
                p.setRepoUrl(repoUrl);
                return p;
            });
            project.setGlobalSecrets(secretsToSave);
            projectRepository.save(project);
        }

        // 🟢 2. Build Unified Runtime Variable State Map
        // Merge baseline global secrets with incoming request parameters (request
        // payload wins on key collisions)
        Map<String, String> structuralDeploymentVars = new HashMap<>();
        structuralDeploymentVars.putAll(projectSecrets);
        structuralDeploymentVars.putAll(incomingEncryptedVars);

        // 🟢 3. Initialize Deployment Task Lifecycle Context Profile Record Entry
        Deployment deployment = new Deployment();
        deployment.setRepoUrl(repoUrl);
        deployment.setEnvironmentVariables(structuralDeploymentVars);

        Deployment saved = deploymentRepository.save(deployment);
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
            // 1. Stop the low-level container worker
            if (deployment.getContainerId() != null) {
                dockerService.stopContainer(deployment.getContainerId());
            }

            // 2. 🟢 NEW: Clean up the live Caddy routing mapping to prevent dangling 502s
            // Extracts the project name (e.g., "my-app") from the repo URL to find matching
            // records
            String[] parts = deployment.getRepoUrl().split("/");
            String rawName = parts[parts.length - 1];
            String projectName = rawName.replace(".git", "").toLowerCase();
            proxyService.removeRoutesForProject(projectName);

            // 3. Persist state changes to database
            deployment.setStatus(Deployment.DeploymentStatus.STOPPED);
            deploymentRepository.save(deployment);
            return ResponseEntity.ok("Deployment stopped and proxy routes cleaned up: " + id);
        }).orElse(ResponseEntity.notFound().build());
    }
}