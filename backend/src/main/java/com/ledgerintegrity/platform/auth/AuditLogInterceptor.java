package com.ledgerintegrity.platform.auth;

import com.ledgerintegrity.platform.auth.persist.AppUser;
import com.ledgerintegrity.platform.auth.persist.AppUserRepository;
import com.ledgerintegrity.platform.auth.persist.AuditLogEntry;
import com.ledgerintegrity.platform.auth.persist.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;

/**
 * SEC-004: append-only audit trail of every authenticated API call (imports, runs,
 * views, exports, decisions). Login attempts are recorded without credentials.
 */
@Component
public class AuditLogInterceptor implements HandlerInterceptor {

    private final AuditLogRepository log;
    private final AppUserRepository users;

    public AuditLogInterceptor(AuditLogRepository log, AppUserRepository users) {
        this.log = log;
        this.users = users;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return;
        if (path.equals("/api/auth/me")) return; // heartbeat noise

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth == null || "anonymousUser".equals(auth.getPrincipal()))
                ? "anonymous" : auth.getName();
        AppUser user = email.equals("anonymous") ? null
                : users.findByEmailIgnoreCase(email).orElse(null);
        try {
            log.save(new AuditLogEntry(
                    user == null ? null : user.getFirmId(),
                    email, request.getMethod(), path, response.getStatus(), Instant.now()));
        } catch (Exception ignored) {
            // auditing must never break the request itself
        }
    }
}
