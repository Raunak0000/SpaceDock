package com.spacedock.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeDetector {
    // Now it just checks if the directory has *something* deployable
    public boolean isValidWorkspace(Path projectDir) {
        // If it has a Dockerfile, it's valid (Custom Runtime)
        if (Files.exists(projectDir.resolve("Dockerfile"))) {
            return true;
        }
        // If it has common dependency files, Nixpacks can build it
        return Files.exists(projectDir.resolve("pom.xml")) || // Java/Maven
                Files.exists(projectDir.resolve("build.gradle")) || // Java/Gradle
                Files.exists(projectDir.resolve("package.json")) || // Node.js
                Files.exists(projectDir.resolve("requirements.txt")) || // Python
                Files.exists(projectDir.resolve("main.go")) || // Go
                Files.exists(projectDir.resolve("Cargo.toml")); // Rust
    }
}
