package com.spacedock.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DockerService {
    private final DockerClient dockerClient;
    private final AtomicInteger nextPort = new AtomicInteger(8000);

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

    public String buildImage(File projectDir, UUID deploymentId) {
        String imageTag = "spacedock-" + deploymentId.toString();
        System.out.println("🔨 Building image: " + imageTag);

        dockerClient.buildImageCmd(projectDir)
                .withTags(Set.of(imageTag))
                .exec(new BuildImageResultCallback())
                .awaitImageId();

        System.out.println("✅ Image built: " + imageTag);
        return imageTag;
    }

    public RunResult runContainer(String imageTag) {
        int hostPort = nextPort.getAndIncrement();
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

    /**
     * Step 5 — deletes the cloned workspace folder after image is built.
     * The container keeps running; we no longer need the source code on disk.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        try {
            Files.walk(workspaceDir)
                    .sorted(Comparator.reverseOrder()) // delete files before their parent dirs
                    .map(Path::toFile)
                    .forEach(File::delete);
            System.out.println("🧹 Workspace cleaned up: " + workspaceDir);
        } catch (IOException e) {
            System.err.println("⚠️ Could not clean workspace: " + e.getMessage());
        }
    }

    public record RunResult(String containerId, int hostPort) {}
}
