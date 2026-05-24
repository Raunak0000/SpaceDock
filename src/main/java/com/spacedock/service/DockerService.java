package com.spacedock.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DockerService {

    private static final String WORKSPACE_ROOT =
            Path.of(System.getProperty("user.dir"), "workspaces")
                    .toAbsolutePath().normalize().toString();

    private final DockerClient dockerClient;

    public DockerService() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    // Phase 3 — streams every Docker build log line to the browser
    public String buildImage(File projectDir, UUID deploymentId, LogBroadcaster logBroadcaster) {
        String imageTag = "spacedock-" + deploymentId.toString();
        String idStr = deploymentId.toString();

        // Validate the project directory is within the workspace root (fix #7)
        validatePathWithinWorkspace(projectDir.toPath());

        File dockerfile = new File(projectDir, "Dockerfile");

        try {
            if (dockerfile.exists()) {
                // PATH 1: Traditional Dockerfile Build
                System.out.println("🔨 Dockerfile detected. Building natively...");
                logBroadcaster.broadcastLog(idStr, "🔨 Dockerfile detected. Starting native Docker build...");

                dockerClient.buildImageCmd(projectDir)
                        .withTags(Set.of(imageTag))
                        // Build with --network=none to prevent Dockerfile RUN commands
                        // from making network requests during build
                        .withNetworkMode("none")
                        .exec(new BuildImageResultCallback() {
                            @Override
                            public void onNext(BuildResponseItem item) {
                                if (item.getStream() != null && !item.getStream().trim().isEmpty()) {
                                    logBroadcaster.broadcastLog(idStr, item.getStream().trim());
                                }
                                super.onNext(item);
                            }
                        })
                        .awaitImageId();

            } else {
                // PATH 2: Zero-Config Nixpacks Build
                System.out.println("🪄 No Dockerfile found. Engaging Nixpacks Auto-Build...");
                logBroadcaster.broadcastLog(idStr, "🪄 No Dockerfile found. Analyzing source code with Nixpacks...");

                // Resolve canonical path to prevent symlink escapes (fix #7)
                String canonicalPath = projectDir.getCanonicalPath();
                ProcessBuilder pb = new ProcessBuilder(
                        "nixpacks", "build", canonicalPath, "--name", imageTag);
                pb.redirectErrorStream(true); // Merge stderr into stdout
                Process process = pb.start();

                // Stream Nixpacks logs directly to the WebSockets browser frontend
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[NIXPACKS] " + line);
                        logBroadcaster.broadcastLog(idStr, "📦 " + line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Nixpacks build failed with exit code " + exitCode);
                }
            }

            System.out.println("✅ Image built successfully: " + imageTag);
            logBroadcaster.broadcastLog(idStr, "✅ Image built successfully!");
            return imageTag;

        } catch (Exception e) {
            throw new RuntimeException("Build process failed: " + e.getMessage(), e);
        }
    }

    public RunResult runContainer(String imageTag, Map<String, String> envVars) {
        ExposedPort containerPort = ExposedPort.tcp(8080);
        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindPort(0));

        long memoryLimit = 512L * 1024 * 1024;
        long cpuLimit = 1_000_000_000L;

        // ── Container Security Hardening (fix #6) ──
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withMemory(memoryLimit)
                .withNanoCPUs(cpuLimit)
                // Drop ALL Linux capabilities — container gets zero elevated privileges
                .withCapDrop(Capability.ALL)
                // Prevent processes from gaining new privileges (e.g., via setuid binaries)
                .withSecurityOpts(List.of("no-new-privileges"))
                // Limit number of processes to prevent fork bombs
                .withPidsLimit(100L);

        // ---------------------------
        List<String> envList = new ArrayList<>();
        if (envVars != null) {
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                envList.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        String containerId = dockerClient.createContainerCmd(imageTag)
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig)
                .withEnv(envList)
                .exec()
                .getId();

        // 2. Start the container
        dockerClient.startContainerCmd(containerId).exec();

        // 3. Inspect the running container to ask Docker which port it actually
        // assigned
        var inspectInfo = dockerClient.inspectContainerCmd(containerId).exec();

        Ports.Binding[] assignedBindings = inspectInfo.getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(containerPort);

        if (assignedBindings == null || assignedBindings.length == 0) {
            dockerClient.stopContainerCmd(containerId).exec();
            throw new RuntimeException("Docker failed to assign a host port to the container.");
        }

        // Parse the dynamically assigned port
        int assignedHostPort = Integer.parseInt(assignedBindings[0].getHostPortSpec());

        System.out.println("🚀 Container running on dynamically assigned port " + assignedHostPort);
        System.out.println("   Container ID: " + containerId);

        return new RunResult(containerId, assignedHostPort);
    }

    /**
     * Safely cleans up a workspace directory without following symlinks (fix #9).
     * Every path is validated to be within the workspace root before deletion.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        try {
            // Resolve to canonical path to detect symlink escapes
            Path canonicalDir = workspaceDir.toRealPath();
            if (!canonicalDir.startsWith(WORKSPACE_ROOT)) {
                System.err.println("⚠️ SECURITY: Refusing to clean path outside workspace: " + canonicalDir);
                return;
            }

            // Walk WITHOUT following symlinks — only delete real files within the workspace
            Files.walk(canonicalDir) // default: no FOLLOW_LINKS
                    .sorted(Comparator.reverseOrder())
                    .filter(path -> {
                        // Double-check every individual path is within workspace root
                        try {
                            return path.toRealPath().startsWith(WORKSPACE_ROOT);
                        } catch (IOException e) {
                            return false; // Skip paths we can't resolve
                        }
                    })
                    .map(Path::toFile)
                    .forEach(File::delete);
            System.out.println("🧹 Workspace cleaned up: " + workspaceDir);
        } catch (IOException e) {
            System.err.println("⚠️ Could not clean workspace: " + e.getMessage());
        }
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).exec();
            System.out.println("🛑 Container stopped: " + containerId);
        } catch (Exception e) {
            System.err.println("⚠️ Could not stop container: " + e.getMessage());
        }
    }

    public record RunResult(String containerId, int hostPort) {
    }

    public Set<String> getRunningContainerIds() {
        return dockerClient.listContainersCmd()
                .withStatusFilter(List.of("running"))
                .exec()
                .stream()
                .map(container -> container.getId())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Validates that a path is within the workspace root directory.
     * Prevents path traversal via symlinks or relative paths.
     */
    private void validatePathWithinWorkspace(Path path) {
        try {
            String canonical = path.toFile().getCanonicalPath();
            if (!canonical.startsWith(WORKSPACE_ROOT)) {
                throw new SecurityException(
                        "Path escapes workspace root: " + canonical);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot resolve path: " + path, e);
        }
    }
}