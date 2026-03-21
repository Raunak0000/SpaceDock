package com.spacedock.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    public GitService(RuntimeDetector runtimeDetector, DockerService dockerService) {
        this.runtimeDetector = runtimeDetector;
        this.dockerService = dockerService;
    }

    @Async
    public void deploy(String repoUrl, UUID deploymentId) {
        Path workspacePath = null;
        try {
            // Step 2 — Clone the repo
            workspacePath = cloneRepository(repoUrl, deploymentId);

            // Verify the repo has a Dockerfile — if not, we stop here
            if (!runtimeDetector.hasDockerfile(workspacePath)) {
                System.err.println("❌ No Dockerfile found in repo. Deployment aborted.");
                System.err.println("   SpaceDock requires a Dockerfile in the root of your repository.");
                return;
            }

            // Step 3 — Build the Docker image from the Dockerfile
            String imageTag = dockerService.buildImage(workspacePath.toFile(), deploymentId);

            // Step 5 — Cleanup the workspace (image is built, we don't need source anymore)
            dockerService.cleanupWorkspace(workspacePath);

            // Step 4 — Run the container
            DockerService.RunResult result = dockerService.runContainer(imageTag);

            System.out.println("🌍 Deployment live at http://localhost:" + result.hostPort());

        } catch (Exception e) {
            System.err.println("❌ Deployment pipeline failed: " + e.getMessage());
            e.printStackTrace();
            // Attempt cleanup even on failure so we don't leave junk on disk
            if (workspacePath != null) {
                dockerService.cleanupWorkspace(workspacePath);
            }
        }
    }
    private Path cloneRepository(String repoUrl, UUID deploymentId) throws GitAPIException {
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
