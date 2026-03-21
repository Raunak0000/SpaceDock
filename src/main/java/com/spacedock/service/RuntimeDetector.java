package com.spacedock.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeDetector {
    // spacedock requires the repo to contain a dockerfile
    // return true if found, false if deployment is rejected

    public boolean hasDockerfile(Path projectDir) {
        return Files.exists(projectDir.resolve("Dockerfile"));
    }
}
