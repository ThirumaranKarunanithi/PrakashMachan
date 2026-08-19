package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.vendor.VendorImportService;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEventRepository;
import com.ledgerintegrity.platform.vendor.persist.VendorRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagements/{id}/vendor-data")
public class VendorController {

    private final VendorImportService importService;
    private final VendorRecordRepository vendors;
    private final AuditTrailEventRepository auditTrail;
    private final com.ledgerintegrity.platform.vendor.AuditTrailAnalysisService analysisService;
    private final TenantGuard guard;

    public VendorController(VendorImportService importService,
                            VendorRecordRepository vendors,
                            AuditTrailEventRepository auditTrail,
                            com.ledgerintegrity.platform.vendor.AuditTrailAnalysisService analysisService,
                            TenantGuard guard) {
        this.guard = guard;
        this.importService = importService;
        this.vendors = vendors;
        this.auditTrail = auditTrail;
        this.analysisService = analysisService;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        guard.engagement(id);
        return Map.of(
                "vendors", vendors.countByEngagementId(id),
                "auditTrailEvents", auditTrail.countByEngagementId(id));
    }

    @PostMapping(value = "/vendors", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VendorImportService.ImportOutcome importVendors(@PathVariable UUID id,
                                                           @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id);
        return outcomeOr422(importService.importVendorMaster(id, name(file, "vendor_master.csv"), file.getBytes()));
    }

    @PostMapping(value = "/audit-trail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VendorImportService.ImportOutcome importAuditTrail(@PathVariable UUID id,
                                                              @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id);
        return outcomeOr422(importService.importAuditTrail(id, name(file, "audit_trail.csv"), file.getBytes()));
    }

    /** ATR-002/003: coverage-gap and configuration-event analysis of the imported audit trail. */
    @PostMapping("/analyze")
    public com.ledgerintegrity.platform.vendor.AuditTrailAnalysisService.CompletenessReport analyze(@PathVariable UUID id) {
        guard.engagement(id);
        return analysisService.analyze(id);
    }

    private static VendorImportService.ImportOutcome outcomeOr422(VendorImportService.ImportOutcome outcome) {
        if (!outcome.problems().isEmpty() && outcome.added() == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.join(" | ", outcome.problems()));
        }
        return outcome;
    }

    private static String name(MultipartFile f, String fallback) {
        String n = f.getOriginalFilename();
        return (n == null || n.isBlank()) ? fallback : n;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
