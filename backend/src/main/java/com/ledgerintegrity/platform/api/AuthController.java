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

    private final boolean demoAutologin;
    private final com.ledgerintegrity.platform.auth.TotpService totp;

    public AuthController(FirmRepository firms, AppUserRepository users,
                          PasswordEncoder passwordEncoder, CurrentUser currentUser,
                          com.ledgerintegrity.platform.auth.TotpService totp,
                          @org.springframework.beans.factory.annotation.Value("${app.demo-autologin:false}") boolean demoAutologin) {
        this.firms = firms;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.totp = totp;
        this.demoAutologin = demoAutologin;
    }

    public record RegisterFirmRequest(@NotBlank String firmName, @NotBlank String displayName,
                                      @NotBlank String email, @NotBlank String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String mfaCode) {}

    /** Returned when the password is right but the account requires a TOTP code. */
    public record MfaChallenge(boolean mfaRequired) {}

    public record MeDto(String email, String displayName, String role, String firmId, String firmName,
                        boolean passwordResetRequired, boolean mfaEnabled) {}

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
                firm.getId().toString(), firm.getName(), user.isPasswordResetRequired(), user.isMfaEnabled());
    }

    /**
     * Demo mode (APP_DEMO_AUTOLOGIN): signs the visitor straight into a shared demo firm,
     * creating it on first use. PARTNER role, so demo visitors cannot secure-delete data.
     * Disable by setting APP_DEMO_AUTOLOGIN=false — the endpoint then returns 404.
     */
    @PostMapping("/demo")
    @Transactional
    public MeDto demo(HttpServletRequest http) {
        if (!demoAutologin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo mode is not enabled.");
        }
        AppUser user = users.findByEmailIgnoreCase(DEMO_EMAIL).orElseGet(() -> {
            Firm firm = firms.findByNameIgnoreCase(DEMO_FIRM_NAME).orElseGet(() ->
                    firms.save(new Firm(UUID.randomUUID(), DEMO_FIRM_NAME, Instant.now())));
            return users.save(new AppUser(UUID.randomUUID(), firm.getId(), DEMO_EMAIL,
                    passwordEncoder.encode(UUID.randomUUID().toString()), "Demo Partner",
                    AppUser.Role.PARTNER, Instant.now()));
        });
        establishSession(user, http);
        Firm firm = firms.findById(user.getFirmId()).orElseThrow();
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName(), user.isPasswordResetRequired(), user.isMfaEnabled());
    }

    private static final String DEMO_FIRM_NAME = "Demo Firm";
    private static final String DEMO_EMAIL = "demo@demo.firm";

    @PostMapping("/login")
    public Object login(@RequestBody LoginRequest req, HttpServletRequest http) {
        AppUser user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        if (user.isMfaEnabled()) {
            if (req.mfaCode() == null || req.mfaCode().isBlank()) {
                return new MfaChallenge(true); // password ok, no session yet - the UI asks for the code
            }
            if (!totp.verify(user.getTotpSecret(), req.mfaCode().trim())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticator code.");
            }
        }
        establishSession(user, http);
        Firm firm = firms.findById(user.getFirmId()).orElseThrow();
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName(), user.isPasswordResetRequired(), user.isMfaEnabled());
    }

    @GetMapping("/me")
    public MeDto me() {
        AppUser user = currentUser.require();
        Firm firm = firms.findById(user.getFirmId()).orElseThrow();
        return new MeDto(user.getEmail(), user.getDisplayName(), user.getRole().name(),
                firm.getId().toString(), firm.getName(), user.isPasswordResetRequired(), user.isMfaEnabled());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest http) {
        SecurityContextHolder.clearContext();
        if (http.getSession(false) != null) http.getSession(false).invalidate();
        return Map.of("status", "logged out");
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    /** First-login reset for provisioned accounts, and self-service change for everyone. */
    @PostMapping("/change-password")
    @Transactional
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest req) {
        AppUser user = currentUser.require();
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is incorrect.");
        }
        if (req.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters.");
        }
        if (req.newPassword().equals(req.currentPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must differ from the current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setPasswordResetRequired(false);
        users.save(user);
        return Map.of("status", "password changed");
    }

    // ---------- MFA (SEC-001 / AC-15) ----------

    public record MfaSetupResponse(String secret, String otpauthUri) {}

    public record MfaCodeRequest(@NotBlank String code) {}

    /** Step 1: generate a secret. Not active until /mfa/enable verifies a code. */
    @PostMapping("/mfa/setup")
    @Transactional
    public MfaSetupResponse mfaSetup() {
        AppUser user = currentUser.require();
        String secret = totp.generateSecret();
        user.setTotpSecret(secret);
        user.setMfaEnabled(false);
        users.save(user);
        return new MfaSetupResponse(secret, totp.uri(secret, user.getEmail()));
    }

    /** Step 2: prove the authenticator works, then enforce it on every login. */
    @PostMapping("/mfa/enable")
    @Transactional
    public MeDto mfaEnable(@RequestBody MfaCodeRequest req) {
        AppUser user = currentUser.require();
        if (user.getTotpSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run /mfa/setup first.");
        }
        if (!totp.verify(user.getTotpSecret(), req.code().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That code does not match - check the authenticator app and try again.");
        }
        user.setMfaEnabled(true);
        users.save(user);
        return me();
    }

    /** Disabling requires a current code - a stolen session alone cannot remove MFA. */
    @PostMapping("/mfa/disable")
    @Transactional
    public MeDto mfaDisable(@RequestBody MfaCodeRequest req) {
        AppUser user = currentUser.require();
        if (!user.isMfaEnabled() || !totp.verify(user.getTotpSecret(), req.code().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid current code is required.");
        }
        user.setMfaEnabled(false);
        user.setTotpSecret(null);
        users.save(user);
        return me();
    }

    /** SPA CSRF flow: authenticated clients fetch the token and echo it as X-XSRF-TOKEN. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest http) {
        var token = (org.springframework.security.web.csrf.CsrfToken) http.getAttribute(
                org.springframework.security.web.csrf.CsrfToken.class.getName());
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
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
