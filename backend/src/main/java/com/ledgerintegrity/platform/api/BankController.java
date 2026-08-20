package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.bank.BankImportService;
import com.ledgerintegrity.platform.bank.BankReconciliationService;
import com.ledgerintegrity.platform.bank.persist.BankLedgerLineRepository;
import com.ledgerintegrity.platform.bank.persist.BankMatchResult;
import com.ledgerintegrity.platform.bank.persist.BankMatchResultRepository;
import com.ledgerintegrity.platform.bank.persist.BankStatementLineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagements/{id}/bank")
public class BankController {

    private final BankImportService importService;
    private final BankReconciliationService reconciliation;
    private final BankMatchResultRepository matches;
    private final BankStatementLineRepository statements;
    private final BankLedgerLineRepository ledger;
    private final TenantGuard guard;

    private final com.ledgerintegrity.platform.auth.CurrentUser currentUser;

    public BankController(BankImportService importService,
                          BankReconciliationService reconciliation,
                          BankMatchResultRepository matches,
                          BankStatementLineRepository statements,
                          BankLedgerLineRepository ledger,
                          TenantGuard guard,
                          com.ledgerintegrity.platform.auth.CurrentUser currentUser) {
        this.guard = guard;
        this.currentUser = currentUser;
        this.importService = importService;
        this.reconciliation = reconciliation;
        this.matches = matches;
        this.statements = statements;
        this.ledger = ledger;
    }

    public record MatchDto(String matchType, LocalDate date, String reference, String description,
                           long amountPaise, boolean outflow, String voucherIds, Integer dateGapDays) {
        static MatchDto from(BankMatchResult m) {
            return new MatchDto(m.getMatchType().name(), m.getDate(), m.getReference(), m.getDescription(),
                    m.getAmountPaise(), m.isOutflow(), m.getVoucherIds(), m.getDateGapDays());
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        guard.engagement(id);
        return Map.of(
                "statementLines", statements.countByEngagementId(id),
                "ledgerLines", ledger.countByEngagementId(id));
    }

    @PostMapping(value = "/statement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankImportService.ImportOutcome importStatement(@PathVariable UUID id,
                                                           @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id);
        return outcomeOr422(importService.importStatement(id, name(file, "bank_statement.csv"), file.getBytes()));
    }

    @PostMapping(value = "/ledger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankImportService.ImportOutcome importLedger(@PathVariable UUID id,
                                                        @RequestParam("file") MultipartFile file) throws IOException {
        guard.engagement(id);
        return outcomeOr422(importService.importLedger(id, name(file, "bank_ledger.csv"), file.getBytes()));
    }

    @PostMapping("/reconcile")
    public Map<String, Object> reconcile(@PathVariable UUID id) {
        guard.engagement(id);
        try {
            var r = reconciliation.reconcile(id);
            Map<String, Object> out = new LinkedHashMap<>();
            r.counts().forEach((k, v) -> out.put(k.name().toLowerCase(), v));
            out.put("statementNetPaise", r.statementNetPaise());
            out.put("ledgerNetPaise", r.ledgerNetPaise());
            out.put("unexplainedPaise", r.unexplainedPaise());
            out.put("exceptionsCreated", r.exceptionsCreated());
            out.put("skippedExisting", r.skippedExisting());
            return out;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/items")
    public List<MatchDto> items(@PathVariable UUID id, @RequestParam String type) {
        guard.engagement(id);
        BankMatchResult.MatchType matchType;
        try {
            matchType = BankMatchResult.MatchType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown match type: " + type);
        }
        return matches.findByEngagementIdAndMatchTypeOrderByAmountPaiseDesc(id, matchType).stream()
                .limit(500).map(MatchDto::from).toList();
    }

    public record ManualLinkRequest(String statementReference, String voucherId, String reason) {}

    public record ManualLinkDto(String statementReference, String voucherId, String reason,
                                String decidedBy, java.time.Instant decidedAt) {}

    /** BKR-003: reviewer approves a pairing manually — logged and applied on the next reconcile. */
    @PostMapping("/manual-links")
    public ManualLinkDto createManualLink(@PathVariable UUID id, @RequestBody ManualLinkRequest req) {
        guard.engagement(id);
        try {
            var m = reconciliation.manualLink(id, req.statementReference(), req.voucherId(),
                    req.reason(), currentUser.actorLabel());
            return new ManualLinkDto(m.getStatementReference(), m.getVoucherId(), m.getReason(),
                    m.getDecidedBy(), m.getDecidedAt());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A manual match already exists for that statement reference.");
        }
    }

    @GetMapping("/manual-links")
    public List<ManualLinkDto> listManualLinks(@PathVariable UUID id) {
        guard.engagement(id);
        return reconciliation.manualLinks(id).stream()
                .map(m -> new ManualLinkDto(m.getStatementReference(), m.getVoucherId(), m.getReason(),
                        m.getDecidedBy(), m.getDecidedAt()))
                .toList();
    }

    private static BankImportService.ImportOutcome outcomeOr422(BankImportService.ImportOutcome outcome) {
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
