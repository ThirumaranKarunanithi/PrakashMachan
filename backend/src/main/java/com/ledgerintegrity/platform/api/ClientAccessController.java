package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.auth.persist.AppUser;
import com.ledgerintegrity.platform.auth.persist.AppUserRepository;
import com.ledgerintegrity.platform.engagement.Engagement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff side of CDC-002: create and list the client users who may access one
 * engagement's evidence portal. A client user sees that portal and nothing else.
 */
@RestController
@RequestMapping("/api/engagements/{id}/client-users")
public class ClientAccessController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TenantGuard guard;

    public ClientAccessController(AppUserRepository users, PasswordEncoder passwordEncoder, TenantGuard guard) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
    }

    public record CreateClientRequest(String email, String displayName, String password) {}

    public record ClientUserDto(String email, String displayName, Instant createdAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientUserDto create(@PathVariable UUID id, @RequestBody CreateClientRequest req) {
        Engagement engagement = guard.engagement(id);
        if (req.email() == null || req.email().isBlank() || req.displayName() == null || req.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and displayName are required.");
        }
        if (req.password() == null || req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters.");
        }
        if (users.findByEmailIgnoreCase(req.email().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered.");
        }
        AppUser client = new AppUser(UUID.randomUUID(), engagement.getFirmId(),
                req.email().trim().toLowerCase(), passwordEncoder.encode(req.password()),
                req.displayName().trim(), AppUser.Role.CLIENT, engagement.getId(), Instant.now());
        users.save(client);
        return new ClientUserDto(client.getEmail(), client.getDisplayName(), client.getCreatedAt());
    }

    @GetMapping
    public List<ClientUserDto> list(@PathVariable UUID id) {
        guard.engagement(id);
        return users.findAll().stream()
                .filter(u -> u.getRole() == AppUser.Role.CLIENT && id.equals(u.getEngagementId()))
                .map(u -> new ClientUserDto(u.getEmail(), u.getDisplayName(), u.getCreatedAt()))
                .toList();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
