package com.ledgerintegrity.platform.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoiceRepository;
import com.ledgerintegrity.platform.notify.NotificationService;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.RuleRun;
import com.ledgerintegrity.platform.rules.persist.RuleRunRepository;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEventRepository;
import com.ledgerintegrity.platform.vendor.persist.VendorRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes the current rule pack against everything the engagement has imported —
 * ledger vouchers, vendor master, purchase register, audit trail. New findings become
 * exceptions via the shared ExceptionService (idempotent), then cases re-consolidate.
 */
@Service
public class RuleEngineService {

    public record RunResult(RuleRun run, List<ExceptionCase> created, List<InvestigationCase> cases) {}

    private final EngagementRepository engagements;
    private final LedgerEntryRepository entries;
    private final VendorRecordRepository vendors;
    private final PurchaseInvoiceRepository purchases;
    private final AuditTrailEventRepository auditTrail;
    private final ExceptionService exceptionService;
    private final InvestigationCaseRepository cases;
    private final RuleRunRepository runs;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public RuleEngineService(EngagementRepository engagements,
                             LedgerEntryRepository entries,
                             VendorRecordRepository vendors,
                             PurchaseInvoiceRepository purchases,
                             AuditTrailEventRepository auditTrail,
                             ExceptionService exceptionService,
                             InvestigationCaseRepository cases,
                             RuleRunRepository runs,
                             NotificationService notificationService,
                             ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.engagements = engagements;
        this.entries = entries;
        this.vendors = vendors;
        this.purchases = purchases;
        this.auditTrail = auditTrail;
        this.exceptionService = exceptionService;
        this.cases = cases;
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    /** JET-001: an optional, snapshotted definition of the population under test. */
    public record PopulationFilter(java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                                   java.util.Set<String> voucherTypes, java.util.Set<String> users,
                                   Long minAmountPaise) {
        boolean matches(Voucher v) {
            if (dateFrom != null && v.txnDate() != null && v.txnDate().isBefore(dateFrom)) return false;
            if (dateTo != null && v.txnDate() != null && v.txnDate().isAfter(dateTo)) return false;
            if (voucherTypes != null && !voucherTypes.isEmpty() && !voucherTypes.contains(v.type())) return false;
            if (users != null && !users.isEmpty() && (v.userId() == null || !users.contains(v.userId()))) return false;
            if (minAmountPaise != null && v.amountPaise() < minAmountPaise) return false;
            return true;
        }
    }

    @Transactional
    public RunResult run(UUID engagementId, RuleParams params) {
        return run(engagementId, params, null);
    }

    @Transactional
    public RunResult run(UUID engagementId, RuleParams params, PopulationFilter filter) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));

        List<LedgerRow> population = entries.findByEngagementId(engagementId).stream()
                .map(LedgerEntry::toRow)
                .toList();
        List<Voucher> vouchers = Voucher.group(population);
        if (filter != null) {
            vouchers = vouchers.stream().filter(filter::matches).toList();
        }

        RulePack pack = RulePack.current();
        Rule.Context ctx = new Rule.Context(
                engagement.getCloseDate(),
                engagement.getFyStart(),
                params,
                vouchers,
                vendors.findByEngagementId(engagementId),
                purchases.findByEngagementId(engagementId),
                auditTrail.findByEngagementId(engagementId));
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : pack.rules()) findings.addAll(rule.evaluate(ctx));

        RuleRun run = new RuleRun(UUID.randomUUID(), engagementId, pack.version(),
                toJson(java.util.Map.of("params", params, "filter", filter == null ? "none" : filter)), Instant.now());
        run.setPopulationValue(vouchers.stream().mapToLong(Voucher::amountPaise).sum());
        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, run.getId(), findings);
        run.setOutcome(vouchers.size(), findings.size(), raised.created().size(), raised.skipped());
        runs.save(run);

        // NFR-002: notify the firm of new high-priority items, once per run
        long newHigh = raised.created().stream()
                .filter(x -> x.getSeverity() == Finding.Severity.HIGH).count();
        if (newHigh > 0) {
            notificationService.notifyOnce(engagement.getFirmId(), "HIGH_EXCEPTIONS", run.getId().toString(),
                    newHigh + " new high-priority exception(s) raised for " + engagement.getClientName()
                            + " (rule pack " + pack.version() + ").", null);
        }

        return new RunResult(run, raised.created(),
                cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(engagementId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise rule params", e);
        }
    }
}
