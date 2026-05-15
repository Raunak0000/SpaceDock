package com.spacedock.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeDetector {
    public boolean isValidWorkspace(Path projectDir) {
        // As long as the directory exists and Git actually put files in it,
        // let Nixpacks figure out the rest!
        return Files.exists(projectDir) && projectDir.toFile().list().length > 1;
        // Note: > 1 because Git always creates a hidden `.git` folder
    }
}
