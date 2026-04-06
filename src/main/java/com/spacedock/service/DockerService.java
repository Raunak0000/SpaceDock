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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DockerService {

    private final DockerClient dockerClient;
    private final AtomicInteger nextPort = new AtomicInteger(4000);

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
        // Find a free port — never collide with an already-running container
        int hostPort = findFreePort();
        ExposedPort containerPort = ExposedPort.tcp(8080);

        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindPort(hostPort));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings);

        String containerId = dockerClient.createContainerCmd(imageTag)
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig)
                .exec()
                .getId();

        dockerClient.startContainerCmd(containerId).exec();

        System.out.println("🚀 Container running on port " + hostPort);
        System.out.println("   Container ID: " + containerId);

        return new RunResult(containerId, hostPort);
    }

    // Increments until it finds a port nothing is currently bound to
    private int findFreePort() {
        int port = nextPort.getAndIncrement();
        while (!isPortFree(port)) {
            port = nextPort.getAndIncrement();
        }
        return port;
    }

    private boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
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

    public record RunResult(String containerId, int hostPort) {
    }
}