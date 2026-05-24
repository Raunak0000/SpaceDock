package com.spacedock.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter per client IP.
 * Limits POST requests to /api/deployments to prevent resource exhaustion.
 *
 * Defaults: 10 requests per minute per IP.
 */
@Configuration
public class RateLimitFilter {

    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MS = 60_000; // 1 minute

    // Tracks request counts per IP within the current window
    private final Map<String, RateBucket> buckets = new ConcurrentHashMap<>();

    @Bean
    public FilterRegistrationBean<Filter> rateLimitFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitServletFilter());
        registration.addUrlPatterns("/api/deployments");
        registration.setOrder(1); // Run before other filters
        return registration;
    }

    private class RateLimitServletFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                             FilterChain chain) throws IOException, ServletException {

            HttpServletRequest httpRequest = (HttpServletRequest) request;

            // Only rate-limit POST (deploy) requests, not GET (listing)
            if (!"POST".equalsIgnoreCase(httpRequest.getMethod())) {
                chain.doFilter(request, response);
                return;
            }

            String clientIp = getClientIp(httpRequest);
            RateBucket bucket = buckets.compute(clientIp, (ip, existing) -> {
                long now = System.currentTimeMillis();
                if (existing == null || now - existing.windowStart > WINDOW_MS) {
                    return new RateBucket(now, 1);
                }
                existing.count++;
                return existing;
            });

            if (bucket.count > MAX_REQUESTS) {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setStatus(429);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write(
                        "{\"error\": \"Too many deployment requests. Try again in 1 minute.\"}");
                System.out.println("⚠️ Rate limit exceeded for IP: " + clientIp);
                return;
            }

            chain.doFilter(request, response);
        }

        private String getClientIp(HttpServletRequest request) {
            // Check for proxy headers first
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
    }

    private static class RateBucket {
        long windowStart;
        int count;

        RateBucket(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
