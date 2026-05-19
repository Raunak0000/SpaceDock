package com.spacedock.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeDetector {
    public boolean hasDockerfile(Path projectDir) {
        return Files.exists(projectDir.resolve("Dockerfile"));
    }
}
