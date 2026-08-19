package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.persist.AppUser;
import com.ledgerintegrity.platform.auth.persist.AppUserRepository;
import com.ledgerintegrity.platform.auth.persist.Firm;
import com.ledgerintegrity.platform.auth.persist.FirmRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final FirmRepository firms;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;

    public AuthController(FirmRepository firms, AppUserRepository users,
                          PasswordEncoder passwordEncoder, CurrentUser currentUser) {
        this.firms = firms;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    public record RegisterFirmRequest(@NotBlank String firmName, @NotBlank String displayName,
                                      @NotBlank String email, @NotBlank String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record MeDto(String email, String displayName, String role, String firmId, String firmName) {}

    /** Self-serve tenant creation: a new firm plus its first (admin) user. */
    @PostMapping("/register-firm")
    @Transactional
    public MeDto registerFirm(@RequestBody RegisterFirmRequest req, HttpServletRequest http) {
        if (req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters.");
        }
        if (firms.findByNameIgnoreCase(req.firmName().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Firm name already registered.");
        }
        if (users.findByEmailIgnoreCase(req.email().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered.");
        }
        Firm firm = new Firm(UUID.randomUUID(), req.firmName().trim(), Instant.now());
        firms.save(firm);
        AppUser user = new AppUser(UUID.randomUUID(), firm.getId(), req.email().trim().toLowerCase(),
                passwordEncoder.encode(req.password()), req.displayName().trim(),
                AppUser.Role.ADMIN, Instant.now());
        users.save(user);
        establishSession(user, http);
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName());
    }

    @PostMapping("/login")
    public MeDto login(@RequestBody LoginRequest req, HttpServletRequest http) {
        AppUser user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        establishSession(user, http);
        Firm firm = firms.findById(user.getFirmId()).orElseThrow();
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName());
    }

    @GetMapping("/me")
    public MeDto me() {
        AppUser user = currentUser.require();
        Firm firm = firms.findById(user.getFirmId()).orElseThrow();
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest http) {
        SecurityContextHolder.clearContext();
        if (http.getSession(false) != null) http.getSession(false).invalidate();
        return Map.of("status", "logged out");
    }

    private static void establishSession(AppUser user, HttpServletRequest http) {
        var auth = new UsernamePasswordAuthenticationToken(user.getEmail(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        // persist the context in the session so subsequent requests are authenticated
        http.getSession(true).setAttribute(
                "SPRING_SECURITY_CONTEXT", context);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
