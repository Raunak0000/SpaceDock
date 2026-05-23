package com.spacedock.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class ProxyService {

    private static final String CADDY_API = "http://localhost:2019";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Bootstraps Caddy with an HTTP server on port 80 so that
     * dynamic route registrations have a valid target path.
     * Without this, /config/apps/http/servers/srv0/routes doesn't exist.
     */
    @PostConstruct
    public void initCaddyServer() {
        String configJson = """
                {
                    "admin": {
                        "listen": "0.0.0.0:2019"
                    },
                    "apps": {
                        "http": {
                            "servers": {
                                "srv0": {
                                    "listen": [":80"],
                                    "routes": []
                                }
                            }
                        }
                    }
                }
                """;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_API + "/load"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(configJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("🌐 Caddy HTTP server initialized on :80");
            } else {
                System.err.println("⚠️ Failed to initialize Caddy: "
                        + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not reach Caddy API for init: " + e.getMessage());
        }
    }

    public void registerRoute(String deploymentId, String projectName, int port) {
        String uuidSubdomain = deploymentId + ".localhost";
        String projectSubdomain = projectName + ".localhost"; // The permanent static URL

        String routeJson = """
                {
                    "match": [{ "host": ["%s", "%s"] }],
                    "handle": [{
                        "handler": "reverse_proxy",
                        "upstreams": [{ "dial": "127.0.0.1:%d" }] 
                    }]
                }
                """.formatted(uuidSubdomain, projectSubdomain, port);

        try {
            // NOTE the "/0" at the end! This prepends the route so the newest deploy always wins
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_API
                            + "/config/apps/http/servers/srv0/routes/0"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(routeJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("🌐 Static Route active: " + projectSubdomain + " → localhost:" + port);
            } else {
                System.err.println("⚠️ Caddy registration failed: "
                        + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not reach Caddy API: " + e.getMessage());
        }
    }
}