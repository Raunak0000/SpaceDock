package com.spacedock.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.github.dockerjava.api.model.Container;

@Service
public class DockerService {

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

        File dockerfile = new File(projectDir, "Dockerfile");

        try {
            if (dockerfile.exists()) {
                // PATH 1: Traditional Dockerfile Build (Your existing logic)
                System.out.println("🔨 Dockerfile detected. Building natively...");
                logBroadcaster.broadcastLog(idStr, "🔨 Dockerfile detected. Starting native Docker build...");

                dockerClient.buildImageCmd(projectDir)
                        .withTags(Set.of(imageTag))
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

                ProcessBuilder pb = new ProcessBuilder(
                        "nixpacks", "build", projectDir.getAbsolutePath(), "--name", imageTag);
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

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withMemory(memoryLimit)
                .withNanoCPUs(cpuLimit);
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
                .withEnv(envList) // <-- INJECT SECRETS HERE
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

    public void cleanupWorkspace(Path workspaceDir) {
        try {
            Files.walk(workspaceDir)
                    .sorted(Comparator.reverseOrder())
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
}