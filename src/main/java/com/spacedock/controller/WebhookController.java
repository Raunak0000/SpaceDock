package com.spacedock.controller;

import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.service.GitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final GitService gitService;
    private final DeploymentRepository deploymentRepository;

    public WebhookController(GitService gitService, DeploymentRepository deploymentRepository) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
    }

    // GitHub will send a POST request to this exact endpoint
    @PostMapping("/github")
    public ResponseEntity<String> handleGithubPush(@RequestBody Map<String, Object> payload) {
        try {
            // 1. Dig into GitHub's massive JSON payload to find the "repository" object
            @SuppressWarnings("unchecked")
            Map<String, Object> repository = (Map<String, Object>) payload.get("repository");
            
            // 2. Extract the actual GitHub URL (e.g., https://github.com/Raunak0000/spacedock-test-app)
            String repoUrl = (String) repository.get("html_url");

            if (repoUrl == null) {
                return ResponseEntity.badRequest().body("Invalid GitHub payload: Missing repository URL");
            }

            System.out.println("🔔 Webhook received! Auto-deploying: " + repoUrl);

            // 3. Create a new Deployment record in PostgreSQL to track this build
            Deployment deployment = new Deployment();
            deployment.setRepoUrl(repoUrl);
            Deployment saved = deploymentRepository.save(deployment);

            // 4. Trigger your existing asynchronous build pipeline
            gitService.deploy(repoUrl, saved.getId());

            // 5. Tell GitHub we successfully received the message
            return ResponseEntity.ok("Auto-deployment triggered for " + repoUrl);

        } catch (Exception e) {
            System.err.println("Webhook processing failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Webhook error");
        }
    }
}