package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.workpaper.WorkpaperService;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkpaperController {

    private final WorkpaperService service;
    private final WorkpaperRepository workpapers;
    private final TenantGuard guard;

    private final com.ledgerintegrity.platform.auth.CurrentUser currentUser;

    public WorkpaperController(WorkpaperService service, WorkpaperRepository workpapers, TenantGuard guard,
                               com.ledgerintegrity.platform.auth.CurrentUser currentUser) {
        this.service = service;
        this.workpapers = workpapers;
        this.guard = guard;
        this.currentUser = currentUser;
    }

    public record WorkpaperDto(String id, int version, String title, String status, String contentSha256,
                               Instant createdAt,
                               String preparedBy, Instant preparedAt,
                               String reviewedBy, Instant reviewedAt,
                               String approvedBy, Instant approvedAt) {
        static WorkpaperDto from(Workpaper w) {
            return new WorkpaperDto(w.getId().toString(), w.getVersion(), w.getTitle(), w.getStatus().name(),
                    w.getContentSha256(), w.getCreatedAt(),
                    w.getPreparedBy(), w.getPreparedAt(),
                    w.getReviewedBy(), w.getReviewedAt(),
                    w.getApprovedBy(), w.getApprovedAt());
        }
    }

    public record SignRequest(@NotNull Workpaper.Role role) {}

    @PostMapping("/engagements/{id}/workpapers")
    public WorkpaperDto generate(@PathVariable UUID id) {
        guard.engagement(id);
        try {
            return WorkpaperDto.from(service.generate(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/engagements/{id}/workpapers")
    public List<WorkpaperDto> list(@PathVariable UUID id) {
        guard.engagement(id);
        return workpapers.findByEngagementIdOrderByVersionDesc(id).stream().map(WorkpaperDto::from).toList();
    }

    @PostMapping("/workpapers/{id}/sign")
    public WorkpaperDto sign(@PathVariable UUID id, @RequestBody SignRequest req) {
        guard.workpaper(id);
        try {
            return WorkpaperDto.from(service.sign(id, req.role(), currentUser.actorLabel()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** AWP-007: export in a common office-openable format (HTML opens in Word and browsers). */
    @GetMapping(value = "/workpapers/{id}/export.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> export(@PathVariable UUID id) {
        Workpaper w = guard.workpaper(id);
        // stored content is immutable; the current sign-off state is appended as a footer
        String footer = "<hr><table><tr><th>Role</th><th>Name</th><th>Date</th></tr>"
                + signRow("Prepared by", w.getPreparedBy(), w.getPreparedAt())
                + signRow("Reviewed by (Manager)", w.getReviewedBy(), w.getReviewedAt())
                + signRow("Approved by (Partner)", w.getApprovedBy(), w.getApprovedAt())
                + "</table><p style='font-size:8pt;color:#555'>Status: " + w.getStatus()
                + " · Content SHA-256: " + w.getContentSha256() + "</p>";
        String html = w.getContentHtml().replace("</body></html>", footer + "</body></html>");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=workpaper-v" + w.getVersion() + ".html")
                .body(html);
    }

    /** AWP-007: same signed content served as a Word file — Word opens HTML natively. */
    @GetMapping(value = "/workpapers/{id}/export.doc", produces = "application/msword")
    public ResponseEntity<String> exportDoc(@PathVariable UUID id) {
        ResponseEntity<String> html = export(id);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=workpaper.doc")
                .header("Content-Type", "application/msword")
                .body(html.getBody());
    }

    private static String signRow(String role, String name, Instant when) {
        return "<tr><td>" + role + "</td><td>" + (name == null ? "" : name)
                + "</td><td>" + (when == null ? "" : when) + "</td></tr>";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
