package com.spacedock.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.service.GitService;
import com.spacedock.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final GitService gitService;
    private final DeploymentRepository deploymentRepository;
    private final String webhookSecret;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookController(GitService gitService,
                             DeploymentRepository deploymentRepository,
                             @Value("${spacedock.webhook-secret}") String webhookSecret,
                             @Value("${spacedock.encryption-key}") String encryptionKey) {
        this.gitService = gitService;
        this.deploymentRepository = deploymentRepository;
        this.webhookSecret = webhookSecret;
        this.cryptoUtil = new CryptoUtil(encryptionKey);
    }

    // GitHub sends the raw body + X-Hub-Signature-256 header.
    // We accept the raw body as String to compute HMAC before parsing JSON.
    @PostMapping("/github")
    public ResponseEntity<String> handleGithubPush(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader) {
        try {
            // ── Step 1: Validate the GitHub signature ──
            if (signatureHeader == null || signatureHeader.isEmpty()) {
                System.err.println("⚠️ Webhook rejected: Missing X-Hub-Signature-256 header");
                return ResponseEntity.status(401).body("Missing signature header");
            }

            String expectedSignature = "sha256=" + computeHmacSha256(rawBody, webhookSecret);
            if (!secureCompare(expectedSignature, signatureHeader)) {
                System.err.println("⚠️ Webhook rejected: Invalid signature");
                return ResponseEntity.status(401).body("Invalid signature");
            }

            // ── Step 2: Parse the verified payload ──
            Map<String, Object> payload = objectMapper.readValue(
                    rawBody, new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            Map<String, Object> repository = (Map<String, Object>) payload.get("repository");

            if (repository == null) {
                return ResponseEntity.badRequest().body("Invalid GitHub payload: Missing repository object");
            }

            String repoUrl = (String) repository.get("html_url");
            if (repoUrl == null) {
                return ResponseEntity.badRequest().body("Invalid GitHub payload: Missing repository URL");
            }

            System.out.println("🔔 Webhook verified! Auto-deploying: " + repoUrl);

            // ── Step 3: Create deployment with encrypted secrets ──
            Deployment deployment = new Deployment();
            deployment.setRepoUrl(repoUrl);

            Map<String, String> autoSecrets = new HashMap<>();
            autoSecrets.put("SECRET_MESSAGE",
                    cryptoUtil.encrypt("Webhook automation injected this secret! 🎉"));
            deployment.setEnvironmentVariables(autoSecrets);

            Deployment saved = deploymentRepository.save(deployment);

            // ── Step 4: Trigger the async build pipeline ──
            gitService.deploy(repoUrl, saved.getId());

            return ResponseEntity.ok("Auto-deployment triggered for " + repoUrl);

        } catch (Exception e) {
            System.err.println("Webhook processing failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Webhook error");
        }
    }

    /**
     * Computes HMAC-SHA256 of the payload using the webhook secret.
     * This must match the signature GitHub sends in X-Hub-Signature-256.
     */
    private String computeHmacSha256(String payload, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

        // Convert to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    /**
     * Constant-time string comparison to prevent timing attacks on signatures.
     */
    private boolean secureCompare(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}