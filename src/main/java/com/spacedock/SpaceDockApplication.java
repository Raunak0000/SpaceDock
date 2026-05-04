package com.spacedock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SpaceDockApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpaceDockApplication.class, args);
        System.out.println("SpaceDock is online!! ");
    }
}
