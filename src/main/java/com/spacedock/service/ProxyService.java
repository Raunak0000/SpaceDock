package com.spacedock.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class ProxyService {

    private static final String CADDY_API = "http://localhost:2019";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void registerRoute(String deploymentId, int port) {
        String subdomain = deploymentId + ".localhost";

        String routeJson = """
                {
                    "match": [{ "host": ["%s"] }],
                    "handle": [{
                        "handler": "reverse_proxy",
                        "upstreams": [{ "dial": "localhost:%d" }]
                    }]
                }
                """.formatted(subdomain, port);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CADDY_API
                            + "/config/apps/http/servers/srv0/routes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(routeJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("🌐 Route registered: "
                        + subdomain + " → localhost:" + port);
            } else {
                System.err.println("⚠️ Caddy registration failed: "
                        + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not reach Caddy API: " + e.getMessage());
        }
    }
}