package com.spacedock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpaceDockApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpaceDockApplication.class,args);
        System.out.println("SpaceDock is online!! ");
    }
}
