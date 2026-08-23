package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.gst.GstImportService;
import com.ledgerintegrity.platform.gst.GstReconciliationService;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr2bInvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagements/{id}/gst")
public class GstController {

    private final GstImportService importService;
    private final GstReconciliationService reconciliation;
    private final GstMatchResultRepository matches;
    private final PurchaseInvoiceRepository purchases;
    private final Gstr2bInvoiceRepository g2b;
    private final com.ledgerintegrity.platform.gst.persist.SalesInvoiceRepository sales;
    private final com.ledgerintegrity.platform.gst.persist.Gstr1InvoiceRepository g1;
    private final com.ledgerintegrity.platform.gst.persist.Gstr3bSummaryRepository g3b;
    private final com.ledgerintegrity.platform.gst.persist.GstManualMatchRepository manualMatches;
    private final TenantGuard guard;
    private final com.ledgerintegrity.platform.auth.CurrentUser currentUser;

    public GstController(GstImportService importService,
                         GstReconciliationService reconciliation,
                         GstMatchResultRepository matches,
                         PurchaseInvoiceRepository purchases,
                         Gstr2bInvoiceRepository g2b,
                         com.ledgerintegrity.platform.gst.persist.SalesInvoiceRepository sales,
                         com.ledgerintegrity.platform.gst.persist.Gstr1InvoiceRepository g1,
                         com.ledgerintegrity.platform.gst.persist.Gstr3bSummaryRepository g3b,
                         com.ledgerintegrity.platform.gst.persist.GstManualMatchRepository manualMatches,
                         TenantGuard guard,
                         com.ledgerintegrity.platform.auth.CurrentUser currentUser) {
        this.manualMatches = manualMatches;
        this.guard = guard;
        this.currentUser = currentUser;
        this.importService = importService;
        this.reconciliation = reconciliation;
        this.matches = matches;
        this.purchases = purchases;
        this.g2b = g2b;
        this.sales = sales;
        this.g1 = g1;
        this.g3b = g3b;
    }

    public record MatchDto(String category, String gstin, String invoiceNo, String partyName,
                           Long booksTaxablePaise, Long booksTaxPaise,
                           Long g2bTaxablePaise, Long g2bTaxPaise,
                           long taxDiffPaise, String voucherId) {
        static MatchDto from(GstMatchResult m) {
            return new MatchDto(m.getCategory().name(), m.getGstin(), m.getInvoiceNo(), m.getPartyName(),
                    m.getBooksTaxablePaise(), m.getBooksTaxPaise(),
                    m.getG2bTaxablePaise(), m.getG2bTaxPaise(),
                    m.getTaxDiffPaise(), m.getVoucherId());
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return Map.of(
                "purchaseInvoices", purchases.countByEngagementId(id),
                "gstr2bInvoices", g2b.countByEngagementId(id),
                "salesInvoices", sales.countByEngagementId(id),
                "gstr1Invoices", g1.countByEngagementId(id),
                "gstr3bPeriods", g3b.countByEngagementId(id));
    }

    @PostMapping(value = "/purchases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GstImportService.ImportOutcome importPurchases(@PathVariable UUID id,
                                                          @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return outcomeOr422(importService.importPurchaseRegister(id, name(file, "purchase_register.csv"), file.getBytes()));
    }

    @PostMapping(value = "/gstr2b", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GstImportService.ImportOutcome importGstr2b(@PathVariable UUID id,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return outcomeOr422(importService.importGstr2b(id, name(file, "gstr2b.csv"), file.getBytes()));
    }

    @PostMapping("/reconcile")
    public Map<String, Object> reconcile(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        var r = reconciliation.reconcile(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconciliationId", r.reconciliationId().toString());
        r.counts().forEach((k, v) -> out.put(k.name().toLowerCase(), v));
        out.put("itcExposurePaise", r.itcExposurePaise());
        out.put("exceptionsCreated", r.exceptionsCreated());
        out.put("skippedExisting", r.skippedExisting());
        return out;
    }

    @GetMapping("/matches")
    public List<MatchDto> listMatches(@PathVariable UUID id, @RequestParam(required = false) String category) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        List<GstMatchResult> rows;
        if (category == null || category.isBlank()) {
            rows = matches.findBySide(id, com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side.PURCHASE, true);
        } else {
            GstMatchResult.Category cat;
            try {
                cat = GstMatchResult.Category.valueOf(category.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + category);
            }
            rows = matches.findBySideAndCategory(id, com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side.PURCHASE, cat, true);
        }
        return rows.stream().limit(500).map(MatchDto::from).toList();
    }

    @PostMapping(value = "/sales", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GstImportService.ImportOutcome importSales(@PathVariable UUID id,
                                                      @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return outcomeOr422(importService.importSalesRegister(id, name(file, "sales_register.csv"), file.getBytes()));
    }

    @PostMapping(value = "/gstr1", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GstImportService.ImportOutcome importGstr1(@PathVariable UUID id,
                                                      @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return outcomeOr422(importService.importGstr1(id, name(file, "gstr1.csv"), file.getBytes()));
    }

    @PostMapping(value = "/gstr3b-summary", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GstImportService.ImportOutcome importGstr3b(@PathVariable UUID id,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return outcomeOr422(importService.importGstr3b(id, name(file, "gstr3b.csv"), file.getBytes()));
    }

    @PostMapping("/reconcile-sales")
    public Map<String, Object> reconcileSales(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        var r = reconciliation.reconcileSales(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconciliationId", r.reconciliationId().toString());
        r.counts().forEach((k, v) -> out.put(k.name().toLowerCase(), v));
        out.put("taxExposurePaise", r.itcExposurePaise());
        out.put("exceptionsCreated", r.exceptionsCreated());
        out.put("skippedExisting", r.skippedExisting());
        return out;
    }

    @PostMapping("/reconcile-3b")
    public GstReconciliationService.Gstr3bResult reconcile3b(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return reconciliation.reconcile3b(id);
    }

    @GetMapping("/sales-matches")
    public List<MatchDto> listSalesMatches(@PathVariable UUID id, @RequestParam(required = false) String category) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        var side = GstMatchResult.Side.SALES;
        List<GstMatchResult> rows;
        if (category == null || category.isBlank()) {
            rows = matches.findBySide(id, side, false);
        } else {
            GstMatchResult.Category cat;
            try {
                cat = GstMatchResult.Category.valueOf(category.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + category);
            }
            rows = matches.findBySideAndCategory(id, side, cat, false);
        }
        return rows.stream().limit(500).map(MatchDto::from).toList();
    }

    public record ManualLinkRequest(GstMatchResult.Side side,
                                    String booksGstin, String booksInvoiceNo,
                                    String portalGstin, String portalInvoiceNo,
                                    String reason) {}

    public record ManualLinkDto(String id, String side, String booksGstin, String booksInvoiceNo,
                                String portalGstin, String portalInvoiceNo,
                                String reason, String decidedBy, java.time.Instant decidedAt) {
        static ManualLinkDto from(com.ledgerintegrity.platform.gst.persist.GstManualMatch m) {
            return new ManualLinkDto(m.getId().toString(), m.getSide().name(),
                    m.getBooksGstin(), m.getBooksInvoiceNo(), m.getPortalGstin(), m.getPortalInvoiceNo(),
                    m.getReason(), m.getDecidedBy(), m.getDecidedAt());
        }
    }

    /** GST-007: record a manual invoice link with a documented reason. */
    @PostMapping("/manual-links")
    public ManualLinkDto createManualLink(@PathVariable UUID id, @RequestBody ManualLinkRequest req) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        try {
            return ManualLinkDto.from(reconciliation.manualLink(id,
                    req.side() == null ? GstMatchResult.Side.PURCHASE : req.side(),
                    req.booksGstin(), req.booksInvoiceNo(), req.portalGstin(), req.portalInvoiceNo(),
                    req.reason(), currentUser.actorLabel()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A manual link already exists for that books invoice.");
        }
    }

    /** GST-007: manual decisions are logged and reviewable. */
    @GetMapping("/manual-links")
    public List<ManualLinkDto> listManualLinks(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return manualMatches.findByEngagementIdOrderByDecidedAtDesc(id).stream()
                .map(ManualLinkDto::from).toList();
    }

    /** GST-009: entity-level vs registration-level view of the reconciliation. */
    @GetMapping("/registration-summary")
    public List<Map<String, Object>> registrationSummary(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        return reconciliation.registrationSummary(id);
    }

    /** GST-008: pre-filing correction schedule with owner/action/financial effect columns. */
    @GetMapping(value = "/correction-schedule.csv", produces = "text/csv")
    public ResponseEntity<String> correctionSchedule(@PathVariable UUID id) {
        guard.engagement(id, com.ledgerintegrity.platform.engagement.Module.GST);
        var rows = reconciliation.correctionSchedule(id);
        List<List<String>> csvRows = new java.util.ArrayList<>();
        for (var r : rows) {
            csvRows.add(List.of(r.side(), r.category(), r.reference(), r.invoiceOrPeriod(), r.party(),
                    r.booksTaxablePaise() == null ? "" : String.format("%.2f", r.booksTaxablePaise() / 100.0),
                    r.portalTaxablePaise() == null ? "" : String.format("%.2f", r.portalTaxablePaise() / 100.0),
                    r.booksTaxPaise() == null ? "" : String.format("%.2f", r.booksTaxPaise() / 100.0),
                    r.portalTaxPaise() == null ? "" : String.format("%.2f", r.portalTaxPaise() / 100.0),
                    String.format("%.2f", r.taxEffectPaise() / 100.0),
                    r.suggestedAction(),
                    "", "")); // owner + due date: assigned by the GST professional
        }
        String csv = com.ledgerintegrity.platform.common.Csv.serialize(
                List.of("side", "category", "gstin_or_period", "invoice_or_period", "party",
                        "books_taxable_inr", "portal_taxable_inr", "books_tax_inr", "portal_tax_inr", "tax_effect_inr",
                        "suggested_action", "owner", "due_date"),
                csvRows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=gst-correction-schedule.csv")
                .body(csv);
    }

    private static GstImportService.ImportOutcome outcomeOr422(GstImportService.ImportOutcome outcome) {
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
