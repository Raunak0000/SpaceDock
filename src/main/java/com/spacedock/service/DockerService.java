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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

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
    public String buildImage(File projectDir, UUID deploymentId,
            LogBroadcaster logBroadcaster) {
        String imageTag = "spacedock-" + deploymentId.toString();
        String idStr = deploymentId.toString();

        System.out.println("🔨 Building image: " + imageTag);
        logBroadcaster.broadcastLog(idStr, "🔨 Starting Docker build...");

        dockerClient.buildImageCmd(projectDir)
                .withTags(Set.of(imageTag))
                .exec(new BuildImageResultCallback() {
                    @Override
                    public void onNext(BuildResponseItem item) {
                        // Called for every line Docker outputs during the build
                        if (item.getStream() != null) {
                            String line = item.getStream().trim();
                            if (!line.isEmpty()) {
                                System.out.println("[BUILD] " + line);
                                // This is what streams to the browser in real time
                                logBroadcaster.broadcastLog(idStr, line);
                            }
                        }
                        super.onNext(item);
                    }
                })
                .awaitImageId();

        System.out.println("✅ Image built: " + imageTag);
        logBroadcaster.broadcastLog(idStr, "✅ Image built successfully!");
        return imageTag;
    }

    public RunResult runContainer(String imageTag) {
        ExposedPort containerPort = ExposedPort.tcp(8080);

        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindPort(0));

        // --- NEW RESOURCE LIMITS ---
        // 512 MB in bytes
        long memoryLimit = 512L * 1024 * 1024;

        // 1.0 CPU Core (Docker expects this in NanoCPUs: 1 core = 1,000,000,000)
        long cpuLimit = 1_000_000_000L;

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withMemory(memoryLimit)
                .withNanoCPUs(cpuLimit);
        // ---------------------------

        String containerId = dockerClient.createContainerCmd(imageTag)
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig)
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

}