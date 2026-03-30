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

    public GitService(RuntimeDetector runtimeDetector,
            DockerService dockerService,
            DeploymentRepository deploymentRepository) {
        this.runtimeDetector = runtimeDetector;
        this.dockerService = dockerService;
        this.deploymentRepository = deploymentRepository;
    }

    @Async
    public void deploy(String repoUrl, UUID deploymentId) {
        Path workspacePath = null;
        try {
            // Update status to CLONING before we start
            updateStatus(deploymentId, Deployment.DeploymentStatus.CLONING);
            workspacePath = cloneRepository(repoUrl, deploymentId);

            // Check for Dockerfile
            if (!runtimeDetector.hasDockerfile(workspacePath)) {
                System.err.println("❌ No Dockerfile found. Deployment aborted.");
                updateStatus(deploymentId, Deployment.DeploymentStatus.FAILED);
                dockerService.cleanupWorkspace(workspacePath);
                return;
            }

            // Update status to BUILDING before Docker build starts
            updateStatus(deploymentId, Deployment.DeploymentStatus.BUILDING);
            String imageTag = dockerService.buildImage(
                    workspacePath.toFile(), deploymentId);

            // Cleanup workspace — image is baked, source no longer needed
            dockerService.cleanupWorkspace(workspacePath);

            // Run the container
            DockerService.RunResult result = dockerService.runContainer(imageTag);

            // Update status to RUNNING — also save container ID and port
            updateStatusRunning(deploymentId, result.containerId(), result.hostPort());

            System.out.println("🌍 Deployment live at http://localhost:"
                    + result.hostPort());

        } catch (Exception e) {
            System.err.println("❌ Deployment pipeline failed: " + e.getMessage());
            e.printStackTrace();
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

        Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir)
                .call();

        System.out.println("✅ Clone complete");
        return targetDir.toPath();
    }
}
