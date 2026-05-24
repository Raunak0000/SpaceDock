package com.spacedock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spacedock.api-key}")
    private String apiKey;

    @Value("${spacedock.allowed-origin}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — we're a stateless API, not a form-based app
                .csrf(csrf -> csrf.disable())

                // CORS — restrict to our actual frontend origin
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Stateless sessions — no server-side session tracking
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // URL authorization rules
                .authorizeHttpRequests(auth -> auth
                        // WebSocket endpoint must be open for the initial HTTP upgrade
                        .requestMatchers("/ws/**").permitAll()
                        // Static frontend files
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/*.css", "/*.js").permitAll()
                        // Webhooks use their own HMAC signature auth — exempt from API key
                        .requestMatchers("/api/webhooks/**").permitAll()
                        // Everything else under /api/** requires authentication via API key
                        .requestMatchers("/api/**").authenticated()
                        // Deny everything else by default
                        .anyRequest().denyAll()
                )

                // Insert our custom API key filter before Spring's default username/password filter
                .addFilterBefore(
                        new ApiKeyFilter(apiKey),
                        UsernamePasswordAuthenticationFilter.class
                )

                // Disable the default login form and HTTP Basic popup
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
