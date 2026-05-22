package com.spacedock.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeDetector {
    public boolean isValidWorkspace(Path projectDir) {
        return Files.exists(projectDir) && projectDir.toFile().list().length > 1;
    }

    /**
     * Checks whether the project directory contains a Dockerfile or any
     * recognisable project file that Nixpacks can auto-build.
     * Called by GitService before kicking off the build pipeline.
     */
    public boolean hasDockerfile(Path projectDir) {
        if (!isValidWorkspace(projectDir)) {
            return false;
        }
        // Dockerfile → native Docker build
        if (Files.exists(projectDir.resolve("Dockerfile"))) {
            return true;
        }
        // Common project markers that Nixpacks can auto-detect
        String[] buildableMarkers = {
            "package.json",      // Node / JS / TS
            "pom.xml",           // Java (Maven)
            "build.gradle",      // Java (Gradle)
            "requirements.txt",  // Python
            "Pipfile",           // Python
            "go.mod",            // Go
            "Gemfile",           // Ruby
            "Cargo.toml",        // Rust
            "composer.json",     // PHP
            "mix.exs"            // Elixir
        };
        for (String marker : buildableMarkers) {
            if (Files.exists(projectDir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }
}
