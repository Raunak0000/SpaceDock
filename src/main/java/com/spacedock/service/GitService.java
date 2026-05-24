package com.spacedock.service;

import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import com.spacedock.util.CryptoUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GitService {

    private static final String WORKSPACE_DIR = System.getProperty("user.dir") + "/workspaces";

    private final RuntimeDetector runtimeDetector;
    private final DockerService dockerService;
    private final DeploymentRepository deploymentRepository;
    private final LogBroadcaster logBroadcaster;
    private final ProxyService proxyService;
    private final CryptoUtil cryptoUtil;

    public GitService(RuntimeDetector runtimeDetector,
            DockerService dockerService,
            DeploymentRepository deploymentRepository,
            LogBroadcaster logBroadcaster,
            ProxyService proxyService,
            @Value("${spacedock.encryption-key}") String encryptionKey) {
        this.runtimeDetector = runtimeDetector;
        this.dockerService = dockerService;
        this.deploymentRepository = deploymentRepository;
        this.logBroadcaster = logBroadcaster;
        this.proxyService = proxyService;
        this.cryptoUtil = new CryptoUtil(encryptionKey);
    }

    @Async
    public void deploy(String repoUrl, UUID deploymentId) {
        Path workspacePath = null;
        String idStr = deploymentId.toString();
        try {
            Thread.sleep(1000);

            // CLONING
            updateStatus(deploymentId, Deployment.DeploymentStatus.CLONING);
            logBroadcaster.broadcastLog(idStr, "⬇️ Cloning repository...");
            workspacePath = cloneRepository(repoUrl, deploymentId);
            logBroadcaster.broadcastLog(idStr, "✅ Clone complete");

            // Runtime check
            if (!runtimeDetector.isValidWorkspace(workspacePath)) { // 
                logBroadcaster.broadcastLog(idStr,
                        "❌ No valid runtime found (Dockerfile, package.json, etc.). Deployment aborted.");
                updateStatus(deploymentId, Deployment.DeploymentStatus.FAILED);
                dockerService.cleanupWorkspace(workspacePath);
                return;
            }

            // BUILDING
            updateStatus(deploymentId, Deployment.DeploymentStatus.BUILDING);
            String imageTag = dockerService.buildImage(
                    workspacePath.toFile(), deploymentId, logBroadcaster);

            // Cleanup workspace
            dockerService.cleanupWorkspace(workspacePath);
            logBroadcaster.broadcastLog(idStr, "🧹 Workspace cleaned up");

            // RUN — decrypt env vars before injecting into the container
            Deployment deployment = deploymentRepository.findById(deploymentId).orElseThrow();
            Map<String, String> encryptedVars = deployment.getEnvironmentVariables();
            Map<String, String> decryptedVars = new HashMap<>();
            if (encryptedVars != null) {
                for (Map.Entry<String, String> entry : encryptedVars.entrySet()) {
                    decryptedVars.put(entry.getKey(), cryptoUtil.decrypt(entry.getValue()));
                }
            }

            DockerService.RunResult result = dockerService.runContainer(imageTag, decryptedVars);

            // --- ZERO DOWNTIME CLEANUP: Stop older versions of this project ---
            deploymentRepository.findAll().stream()
                    .filter(d -> d.getStatus() == Deployment.DeploymentStatus.RUNNING)
                    .filter(d -> d.getRepoUrl().equals(repoUrl))
                    .filter(d -> !d.getId().equals(deploymentId)) // Don't kill the one we just started!
                    .forEach(old -> {
                        dockerService.stopContainer(old.getContainerId());
                        old.setStatus(Deployment.DeploymentStatus.STOPPED);
                        deploymentRepository.save(old);
                        logBroadcaster.broadcastLog(idStr, "🛑 Stopped previous version (Port: " + old.getPortNumber() + ")");
                    });

            // Persist container info and mark as RUNNING
            updateStatusRunning(deploymentId, result.containerId(), result.hostPort());

            // --- STATIC ROUTING ---
            String projectName = extractProjectName(repoUrl);
            proxyService.registerRoute(idStr, projectName, result.hostPort());

            // Broadcast the clean, permanent subdomain URL
            String liveUrl = "http://" + projectName + ".localhost";
            logBroadcaster.broadcastLog(idStr, "🌍 Static Deployment live at " + liveUrl);
            System.out.println("🌍 Static Deployment live at " + liveUrl);

        } catch (Exception e) {
            System.err.println("❌ Deployment pipeline failed: " + e.getMessage());
            e.printStackTrace();
            logBroadcaster.broadcastLog(idStr,
                    "❌ Deployment failed: " + e.getMessage());
            updateStatus(deploymentId, Deployment.DeploymentStatus.FAILED);
            if (workspacePath != null) {
                dockerService.cleanupWorkspace(workspacePath);
            }
        }
    }

    private void updateStatus(UUID deploymentId,
            Deployment.DeploymentStatus status) {
        deploymentRepository.findById(deploymentId).ifPresent(d -> {
            d.setStatus(status);
            deploymentRepository.save(d);
            System.out.println("📝 Status → " + status);
        });
    }

    private void updateStatusRunning(UUID deploymentId, String containerId, int port) {
        deploymentRepository.findById(deploymentId).ifPresent(d -> {
            d.setStatus(Deployment.DeploymentStatus.RUNNING);
            d.setContainerId(containerId);
            d.setPortNumber(port);
            deploymentRepository.save(d);
            System.out.println("📝 Status → RUNNING on port " + port);
        });
    }

    private Path cloneRepository(String repoUrl,
            UUID deploymentId) throws GitAPIException {
        String folderName = "deployment_" + deploymentId;
        File targetDir = Paths.get(WORKSPACE_DIR, folderName).toFile();

        System.out.println("⬇️  Cloning: " + repoUrl);
        System.out.println("📁 Target:  " + targetDir.getAbsolutePath());

        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir)
                .call()) {
            System.out.println("✅ Clone complete");
        }
        return targetDir.toPath();
    }

    // Extracts "spacedock-test-app" from the GitHub URL
    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String rawName = parts[parts.length - 1];
        return rawName.replace(".git", "").toLowerCase();
    }
}