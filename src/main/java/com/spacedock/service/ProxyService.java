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
                    "listen": "127.0.0.1:2019"
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

    /**
     * Removes all existing Caddy routes that match the given project subdomain.
     * This prevents stale routes (pointing to dead containers) from shadowing
     * the newly registered route, which causes 502 Bad Gateway errors.
     *
     * Iterates in reverse so that removing route[i] doesn't shift the
     * indices of routes we haven't checked yet.
     */
    public void removeRoutesForProject(String projectName) {
        String projectSubdomain = projectName + ".localhost";
        String routesPath = "/config/apps/http/servers/srv0/routes";

        try {
            // Fetch the current routes array from Caddy
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_API + routesPath))
                    .GET()
                    .build();

            HttpResponse<String> getResponse = httpClient.send(
                    getRequest, HttpResponse.BodyHandlers.ofString());

            if (getResponse.statusCode() != 200) {
                System.err.println("⚠️ Could not fetch Caddy routes: " + getResponse.body());
                return;
            }

            String routesJson = getResponse.body();

            // Count how many routes exist (count occurrences of "handler")
            int routeCount = 0;
            int searchFrom = 0;
            while ((searchFrom = routesJson.indexOf("\"handler\"", searchFrom)) != -1) {
                routeCount++;
                searchFrom++;
            }

            // Walk backwards through the routes array
            int removed = 0;
            for (int i = routeCount - 1; i >= 0; i--) {
                // Fetch the specific route to check its host list
                HttpRequest routeRequest = HttpRequest.newBuilder()
                        .uri(URI.create(CADDY_API + routesPath + "/" + i))
                        .GET()
                        .build();

                HttpResponse<String> routeResponse = httpClient.send(
                        routeRequest, HttpResponse.BodyHandlers.ofString());

                if (routeResponse.statusCode() != 200) continue;

                // Check if this route matches our project subdomain
                if (routeResponse.body().contains("\"" + projectSubdomain + "\"")) {
                    // DELETE this route by index
                    HttpRequest deleteRequest = HttpRequest.newBuilder()
                            .uri(URI.create(CADDY_API + routesPath + "/" + i))
                            .DELETE()
                            .build();

                    HttpResponse<String> deleteResponse = httpClient.send(
                            deleteRequest, HttpResponse.BodyHandlers.ofString());

                    if (deleteResponse.statusCode() == 200) {
                        removed++;
                    }
                }
            }

            if (removed > 0) {
                System.out.println("🧹 Removed " + removed + " stale Caddy route(s) for " + projectSubdomain);
            }

        } catch (Exception e) {
            System.err.println("⚠️ Could not clean Caddy routes: " + e.getMessage());
        }
    }

    public void registerRoute(String deploymentId, String projectName, int port) {
        // Clean up any stale routes for this project BEFORE adding the new one
        removeRoutesForProject(projectName);

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
            // NOTE the "/0" at the end! This prepends the route so the newest deploy always
            // wins
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