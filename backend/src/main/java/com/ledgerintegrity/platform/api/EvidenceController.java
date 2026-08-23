package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocument;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocumentRepository;
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

@RestController
@RequestMapping("/api")
public class EvidenceController {

    private final EvidenceService service;
    private final EvidenceRequestRepository requests;
    private final EvidenceDocumentRepository documents;
    private final TenantGuard guard;

    private final com.ledgerintegrity.platform.auth.CurrentUser currentUser;

    public EvidenceController(EvidenceService service,
                              EvidenceRequestRepository requests,
                              EvidenceDocumentRepository documents,
                              TenantGuard guard,
                              com.ledgerintegrity.platform.auth.CurrentUser currentUser) {
        this.guard = guard;
        this.service = service;
        this.requests = requests;
        this.documents = documents;
        this.currentUser = currentUser;
    }

    public record CreateRequest(String title, String description, LocalDate dueDate) {}

    public record DecisionRequest(EvidenceRequest.Status decision, String note) {}

    public record DocumentDto(String id, int version, String fileName, String contentType, long sizeBytes,
                              String sha256, String uploadedBy, Instant uploadedAt) {
        static DocumentDto from(EvidenceDocument d) {
            return new DocumentDto(d.getId().toString(), d.getVersion(), d.getFileName(), d.getContentType(),
                    d.getSizeBytes(), d.getSha256(), d.getUploadedBy(), d.getUploadedAt());
        }
    }

    public record RequestDto(String id, String exceptionId, String title, String description,
                             String requestedBy, LocalDate dueDate, String status, boolean overdue,
                             String decisionNote, String decidedBy, Instant decidedAt,
                             Instant createdAt, List<DocumentDto> documents) {
        static RequestDto from(EvidenceRequest r, List<EvidenceDocument> docs) {
            return new RequestDto(r.getId().toString(), r.getExceptionId().toString(), r.getTitle(),
                    r.getDescription(), r.getRequestedBy(), r.getDueDate(), r.getStatus().name(),
                    r.isOverdue(LocalDate.now()), r.getDecisionNote(), r.getDecidedBy(), r.getDecidedAt(),
                    r.getCreatedAt(), docs.stream().map(DocumentDto::from).toList());
        }
    }

    @PostMapping("/exceptions/{exceptionId}/evidence-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestDto create(@PathVariable UUID exceptionId, @RequestBody CreateRequest req) {
        guard.exception(exceptionId);
        try {
            EvidenceRequest r = service.createRequest(exceptionId, req.title(), req.description(),
                    currentUser.actorLabel(), req.dueDate());
            return RequestDto.from(r, List.of());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/engagements/{id}/evidence-requests")
    public List<RequestDto> list(@PathVariable UUID id) {
        guard.engagement(id);
        return requests.findByEngagementIdOrderByCreatedAtDesc(id).stream()
                .map(r -> RequestDto.from(r, service.documentsOf(r.getId())))
                .toList();
    }

    @PostMapping(value = "/evidence-requests/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentDto upload(@PathVariable UUID id,
                              @RequestParam("file") MultipartFile file) throws IOException {
        guard.evidenceRequest(id);
        try {
            return DocumentDto.from(service.upload(id,
                    file.getOriginalFilename() == null ? "document" : file.getOriginalFilename(),
                    file.getContentType(), file.getBytes(), currentUser.actorLabel()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** CDC-006: sufficiency decision with a documented reason. */
    @PostMapping("/evidence-requests/{id}/decision")
    public RequestDto decide(@PathVariable UUID id, @RequestBody DecisionRequest req) {
        guard.evidenceRequest(id);
        try {
            EvidenceRequest r = service.decide(id, req.decision(), req.note(), currentUser.actorLabel());
            return RequestDto.from(r, service.documentsOf(r.getId()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/evidence-documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        EvidenceDocument d = guard.evidenceDocument(id);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + d.getFileName().replace("\"", "") + "\"")
                .header("Content-Type", d.getContentType())
                .body(service.contentOf(d));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
