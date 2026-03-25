package com.spacedock.repository;

import com.spacedock.model.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {
    Deployment findByContainerId(String containerId); // auto generates the SQL to find the container
}
