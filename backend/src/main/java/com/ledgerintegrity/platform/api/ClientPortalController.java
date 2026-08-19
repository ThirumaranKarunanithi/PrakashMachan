package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.persist.AppUser;
import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocument;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CDC-002: the simplified client evidence portal. A client user sees ONLY their own
 * engagement's requests and uploads — never rule ids, risk scores, exceptions, or any
 * other client. The DTOs here are deliberately sanitized; do not reuse staff DTOs.
 */
@RestController
@RequestMapping("/api/client")
public class ClientPortalController {

    private final CurrentUser currentUser;
    private final EvidenceService evidenceService;
    private final EvidenceRequestRepository requests;

    public ClientPortalController(CurrentUser currentUser,
                                  EvidenceService evidenceService,
                                  EvidenceRequestRepository requests) {
        this.currentUser = currentUser;
        this.evidenceService = evidenceService;
        this.requests = requests;
    }

    public record ClientDocumentDto(String id, int version, String fileName, long sizeBytes,
                                    String uploadedBy, Instant uploadedAt) {}

    public record ClientRequestDto(String id, String title, String description, LocalDate dueDate,
                                   String status, boolean overdue, String responseNote,
                                   List<ClientDocumentDto> documents) {}

    @GetMapping("/requests")
    public List<ClientRequestDto> myRequests() {
        UUID engagementId = clientEngagement();
        return requests.findByEngagementIdOrderByCreatedAtDesc(engagementId).stream()
                .map(this::sanitize)
                .toList();
    }

    @PostMapping(value = "/requests/{requestId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ClientDocumentDto upload(@PathVariable UUID requestId,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        AppUser client = requireClient();
        EvidenceRequest request = requests.findById(requestId)
                .filter(r -> r.getEngagementId().equals(client.getEngagementId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        try {
            EvidenceDocument d = evidenceService.upload(request.getId(),
                    file.getOriginalFilename() == null ? "document" : file.getOriginalFilename(),
                    file.getContentType(), file.getBytes(), client.getEmail());
            return new ClientDocumentDto(d.getId().toString(), d.getVersion(), d.getFileName(),
                    d.getSizeBytes(), d.getUploadedBy(), d.getUploadedAt());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID documentId) {
        AppUser client = requireClient();
        // resolve document -> request -> engagement, all inside the client's scope
        for (EvidenceRequest r : requests.findByEngagementIdOrderByCreatedAtDesc(client.getEngagementId())) {
            for (EvidenceDocument d : evidenceService.documentsOf(r.getId())) {
                if (d.getId().equals(documentId)) {
                    return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=\"" + d.getFileName().replace("\"", "") + "\"")
                            .header("Content-Type", d.getContentType())
                            .body(d.getContent());
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }

    // ---------- helpers ----------

    private UUID clientEngagement() {
        return requireClient().getEngagementId();
    }

    private AppUser requireClient() {
        AppUser user = currentUser.require();
        if (user.getRole() != AppUser.Role.CLIENT || user.getEngagementId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Client portal access only.");
        }
        return user;
    }

    private ClientRequestDto sanitize(EvidenceRequest r) {
        List<ClientDocumentDto> docs = evidenceService.documentsOf(r.getId()).stream()
                .map(d -> new ClientDocumentDto(d.getId().toString(), d.getVersion(), d.getFileName(),
                        d.getSizeBytes(), d.getUploadedBy(), d.getUploadedAt()))
                .toList();
        // the decision note is shown only when the auditor rejected — it is the follow-up
        // instruction addressed to the client; internal context stays internal
        String responseNote = r.getStatus() == EvidenceRequest.Status.REJECTED ? r.getDecisionNote() : null;
        return new ClientRequestDto(r.getId().toString(), r.getTitle(), r.getDescription(), r.getDueDate(),
                r.getStatus().name(), r.isOverdue(LocalDate.now()), responseNote, docs);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
