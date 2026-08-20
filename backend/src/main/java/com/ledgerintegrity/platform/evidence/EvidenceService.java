package com.ledgerintegrity.platform.evidence;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocument;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocumentRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.notify.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * BRD §14: the evidence-request workspace. Requests are created from exceptions
 * (CDC-001), every uploaded file version is preserved with a checksum (CDC-004),
 * and closure requires a documented auditor decision (CDC-006).
 */
@Service
public class EvidenceService {

    private final EvidenceRequestRepository requests;
    private final EvidenceDocumentRepository documents;
    private final ExceptionCaseRepository exceptions;
    private final EngagementRepository engagements;
    private final NotificationService notificationService;

    private final com.ledgerintegrity.platform.rules.ExceptionDecisionService decisions;

    public EvidenceService(EvidenceRequestRepository requests,
                           EvidenceDocumentRepository documents,
                           ExceptionCaseRepository exceptions,
                           EngagementRepository engagements,
                           NotificationService notificationService,
                           com.ledgerintegrity.platform.rules.ExceptionDecisionService decisions) {
        this.requests = requests;
        this.documents = documents;
        this.exceptions = exceptions;
        this.engagements = engagements;
        this.notificationService = notificationService;
        this.decisions = decisions;
    }

    @Transactional
    public EvidenceRequest createRequest(UUID exceptionId, String title, String description,
                                         String requestedBy, LocalDate dueDate) {
        ExceptionCase exception = exceptions.findById(exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown exception: " + exceptionId));
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Title is required.");
        if (requestedBy == null || requestedBy.isBlank()) throw new IllegalArgumentException("requestedBy is required.");

        EvidenceRequest request = new EvidenceRequest(UUID.randomUUID(), exception.getEngagementId(),
                exceptionId, title.trim(), description, requestedBy.trim(), dueDate, Instant.now());
        requests.save(request);

        // the exception is now waiting on the client (BRD §3.3 "Information required");
        // the transition goes through the history service so the prior note survives
        if (exception.getStatus() == ExceptionCase.Status.NEW
                || exception.getStatus() == ExceptionCase.Status.UNDER_REVIEW) {
            decisions.transition(exception, ExceptionCase.Status.INFO_REQUIRED,
                    "Evidence requested: " + title.trim(), requestedBy.trim());
        }
        return request;
    }

    @Transactional
    public EvidenceDocument upload(UUID requestId, String fileName, String contentType,
                                   byte[] content, String uploadedBy) {
        EvidenceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown evidence request: " + requestId));
        if (content == null || content.length == 0) throw new IllegalArgumentException("Empty file.");
        request.markResponded();
        requests.save(request);

        int version = documents.findByRequestIdOrderByVersionAsc(requestId).size() + 1;
        EvidenceDocument doc = new EvidenceDocument(UUID.randomUUID(), requestId, version,
                fileName, contentType == null ? "application/octet-stream" : contentType,
                content, Checksums.sha256Hex(content),
                uploadedBy == null || uploadedBy.isBlank() ? "client" : uploadedBy.trim(), Instant.now());
        documents.save(doc);
        engagements.findById(request.getEngagementId()).ifPresent(e ->
                notificationService.notifyOnce(e.getFirmId(), "EVIDENCE_RESPONSE",
                        request.getId() + ":v" + version,
                        "Response received for \"" + request.getTitle() + "\" (" + e.getClientName()
                                + "), version " + version + ".", null));
        return doc;
    }

    @Transactional
    public EvidenceRequest decide(UUID requestId, EvidenceRequest.Status decision, String note, String decidedBy) {
        EvidenceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown evidence request: " + requestId));
        request.decide(decision, note, decidedBy, Instant.now());
        requests.save(request);
        return request;
    }

    public List<EvidenceDocument> documentsOf(UUID requestId) {
        return documents.findByRequestIdOrderByVersionAsc(requestId);
    }
}
