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
    // This points to the workspaces folder at the root of your project
    private final String WORKSPACE_DIR = "workspaces";

    public Path cloneRepository(String repoUrl, UUID deploymentId) {
        try {
            // Create a unique folder for this specific deployment
            String targetFolderName = "deployment_" + deploymentId.toString();
            File targetDirectory = Paths.get(WORKSPACE_DIR, targetFolderName).toFile();

            System.out.println("⬇️ Initiating clone for: " + repoUrl);
            System.out.println("📁 Target directory: " + targetDirectory.getAbsolutePath());

            // The JGit command that reaches out to the internet
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(targetDirectory)
                    .call();

            System.out.println("✅ Repository successfully cloned into SpaceDock!");

            return targetDirectory.toPath();

        } catch (GitAPIException e) {
            System.err.println("❌ Failed to clone repository: " + e.getMessage());
            throw new RuntimeException("Git clone failed", e);
        }
    }
}
