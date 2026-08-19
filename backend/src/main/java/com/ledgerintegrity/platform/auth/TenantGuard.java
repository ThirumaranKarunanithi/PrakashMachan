package com.ledgerintegrity.platform.auth;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocument;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocumentRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * SEC-001: every engagement-scoped resource is checked against the caller's firm.
 * Resources of other firms answer 404 (not 403) so their existence is never leaked.
 */
@Component
public class TenantGuard {

    private final CurrentUser currentUser;
    private final EngagementRepository engagements;
    private final ExceptionCaseRepository exceptions;
    private final InvestigationCaseRepository investigationCases;
    private final WorkpaperRepository workpapers;
    private final EvidenceRequestRepository evidenceRequests;
    private final EvidenceDocumentRepository evidenceDocuments;

    public TenantGuard(CurrentUser currentUser,
                       EngagementRepository engagements,
                       ExceptionCaseRepository exceptions,
                       InvestigationCaseRepository investigationCases,
                       WorkpaperRepository workpapers,
                       EvidenceRequestRepository evidenceRequests,
                       EvidenceDocumentRepository evidenceDocuments) {
        this.currentUser = currentUser;
        this.engagements = engagements;
        this.exceptions = exceptions;
        this.investigationCases = investigationCases;
        this.workpapers = workpapers;
        this.evidenceRequests = evidenceRequests;
        this.evidenceDocuments = evidenceDocuments;
    }

    public InvestigationCase investigationCase(UUID caseId) {
        InvestigationCase c = investigationCases.findById(caseId).orElseThrow(this::notFound);
        engagement(c.getEngagementId());
        return c;
    }

    public Engagement engagement(UUID engagementId) {
        UUID firmId = currentUser.firmId();
        return engagements.findById(engagementId)
                .filter(e -> firmId.equals(e.getFirmId()))
                .orElseThrow(this::notFound);
    }

    public ExceptionCase exception(UUID exceptionId) {
        ExceptionCase x = exceptions.findById(exceptionId).orElseThrow(this::notFound);
        engagement(x.getEngagementId());
        return x;
    }

    public Workpaper workpaper(UUID workpaperId) {
        Workpaper w = workpapers.findById(workpaperId).orElseThrow(this::notFound);
        engagement(w.getEngagementId());
        return w;
    }

    public EvidenceRequest evidenceRequest(UUID requestId) {
        EvidenceRequest r = evidenceRequests.findById(requestId).orElseThrow(this::notFound);
        engagement(r.getEngagementId());
        return r;
    }

    public EvidenceDocument evidenceDocument(UUID documentId) {
        EvidenceDocument d = evidenceDocuments.findById(documentId).orElseThrow(this::notFound);
        evidenceRequest(d.getRequestId());
        return d;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
