package com.ledgerintegrity.platform.auth;

import com.ledgerintegrity.platform.auth.persist.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Session-based API security (SEC-002/003 groundwork): JSON login issues an HTTP
 * session cookie; every /api route except /api/auth/** requires authentication and
 * returns 401 as JSON (no redirects). CSRF is disabled for the MVP JSON API —
 * revisit alongside MFA and production hardening.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository users) {
        return email -> users.findByEmailIgnoreCase(email)
                .map(u -> User.withUsername(u.getEmail())
                        .password(u.getPasswordHash())
                        .roles(u.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @org.springframework.beans.factory.annotation.Value("${app.cors-allowed-origins:}") String corsOrigins)
            throws Exception {
        // Phase A: CSRF is ON. Tokens live in the server-side session; the SPA fetches
        // one from GET /api/auth/csrf after signing in and echoes it as X-XSRF-TOKEN on
        // every mutating call (works cross-origin, where a token cookie would be
        // unreadable). Credential-carrying entry points are exempt: they establish the
        // session in the first place and are protected by SameSite + CORS.
        var csrfRepo = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        csrfRepo.setHeaderName("X-XSRF-TOKEN");
        var csrfHandler = new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/api/auth/login", "/api/auth/register-firm", "/api/auth/demo"))
                .cors(cors -> cors.configurationSource(corsSource(corsOrigins)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // CDC-002: client users reach ONLY the evidence portal; staff cannot pose as clients
                        .requestMatchers("/api/client/**").hasRole("CLIENT")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "PARTNER", "MANAGER", "ASSOCIATE")
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Authentication required\"}");
                }))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /**
     * When the frontend is hosted on a different origin (e.g. Hostinger static site
     * calling a Railway backend), APP_CORS_ALLOWED_ORIGINS lists the allowed origins.
     * Empty (the default) means same-origin only — no CORS headers are emitted.
     */
    private static org.springframework.web.cors.CorsConfigurationSource corsSource(String origins) {
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        if (origins == null || origins.isBlank()) return source;
        var cfg = new org.springframework.web.cors.CorsConfiguration();
        for (String o : origins.split(",")) {
            String trimmed = o.trim();
            if (!trimmed.isEmpty()) cfg.addAllowedOrigin(trimmed);
        }
        cfg.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(java.util.List.of("*"));
        cfg.setAllowCredentials(true); // session cookie must travel with requests
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
