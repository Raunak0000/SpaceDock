package com.spacedock.service;

import java.util.List;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.spacedock.model.Deployment;
import com.spacedock.repository.DeploymentRepository;

@Component
public class ZombieReaperJob {
    private final DeploymentRepository deploymentRepository;
    private final DockerService dockerService;

    public ZombieReaperJob(DeploymentRepository deploymentRepository, DockerService dockerService) {
        this.deploymentRepository = deploymentRepository;
        this.dockerService = dockerService;
    }

    // Runs every 60 seconds (60000 milliseconds)
    @Scheduled(fixedRate = 60000)
    public void reapZombies() {
        // 1. Ask the DB who *should* be running
        List<Deployment> supposedToBeRunning = deploymentRepository.findAll().stream()
                .filter(d -> d.getStatus() == Deployment.DeploymentStatus.RUNNING)
                .toList();

        if (supposedToBeRunning.isEmpty()) {
            return; // Nothing to check
        }

        // 2. Ask Docker who is *actually* running
        Set<String> actuallyRunningIds = dockerService.getRunningContainerIds();

        // 3. Reconcile
        for (Deployment deployment : supposedToBeRunning) {
            // Docker container IDs are 64 characters. We use startsWith to safely match.
            boolean isAlive = actuallyRunningIds.stream()
                    .anyMatch(id -> id.startsWith(deployment.getContainerId()));

            if (!isAlive) {
                System.out.println(
                        "🧟 Zombie detected! Container " + deployment.getContainerId() + " died unexpectedly.");

                // Update the state so the UI accurately reflects reality
                deployment.setStatus(Deployment.DeploymentStatus.STOPPED);
                deploymentRepository.save(deployment);
            }
        }
    }
}
