package com.spacedock.service;

import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class GitService {

    private static final String WORKSPACE_DIR = System.getProperty("user.dir") + "/workspaces";

    private final RuntimeDetector runtimeDetector;
    private final DockerService dockerService;
    private final DeploymentRepository deploymentRepository;
    private final LogBroadcaster logBroadcaster;

    public GitService(RuntimeDetector runtimeDetector,
            DockerService dockerService,
            DeploymentRepository deploymentRepository,
            LogBroadcaster logBroadcaster) {
        this.runtimeDetector = runtimeDetector;
        this.dockerService = dockerService;
        this.deploymentRepository = deploymentRepository;
        this.logBroadcaster = logBroadcaster;
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

            // Dockerfile check
            if (!runtimeDetector.hasDockerfile(workspacePath)) {
                logBroadcaster.broadcastLog(idStr,
                        "❌ No Dockerfile found. Deployment aborted.");
                updateStatus(deploymentId, Deployment.DeploymentStatus.FAILED);
                dockerService.cleanupWorkspace(workspacePath);
                return;
            }

            // BUILDING — logs stream from inside buildImage()
            updateStatus(deploymentId, Deployment.DeploymentStatus.BUILDING);
            String imageTag = dockerService.buildImage(
                    workspacePath.toFile(), deploymentId);

            // Cleanup
            dockerService.cleanupWorkspace(workspacePath);
            logBroadcaster.broadcastLog(idStr, "🧹 Workspace cleaned up");

            // RUN
            DockerService.RunResult result = dockerService.runContainer(imageTag);
            updateStatusRunning(deploymentId,
                    result.containerId(), result.hostPort());

            logBroadcaster.broadcastLog(idStr,
                    "🌍 Deployment live at http://localhost:" + result.hostPort());
            System.out.println("🌍 Deployment live at http://localhost:"
                    + result.hostPort());

        } catch (Exception e) {
            System.err.println("❌ Deployment pipeline failed: " + e.getMessage());
            e.printStackTrace();
            logBroadcaster.broadcastLog(idStr, "❌ Deployment failed: " + e.getMessage());
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

    private void updateStatusRunning(UUID deploymentId,
            String containerId, int port) {
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
}