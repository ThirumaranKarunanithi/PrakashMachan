package com.ledgerintegrity.platform.bank;

import com.ledgerintegrity.platform.bank.persist.BankLedgerLine;
import com.ledgerintegrity.platform.bank.persist.BankLedgerLineRepository;
import com.ledgerintegrity.platform.bank.persist.BankManualMatch;
import com.ledgerintegrity.platform.bank.persist.BankManualMatchRepository;
import com.ledgerintegrity.platform.bank.persist.BankMatchResult;
import com.ledgerintegrity.platform.bank.persist.BankMatchResult.MatchType;
import com.ledgerintegrity.platform.bank.persist.BankMatchResultRepository;
import com.ledgerintegrity.platform.bank.persist.BankStatementLine;
import com.ledgerintegrity.platform.bank.persist.BankStatementLineRepository;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.rules.ExceptionService;
import com.ledgerintegrity.platform.rules.Finding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BK-01..05: match the bank statement against the books' bank ledger.
 *  - EXACT      same reference, amount, direction and date (BK-01)
 *  - TOLERANCE  same reference/amount/direction, date within the window (BK-02)
 *  - GROUPED    one statement line equals the sum of 2+ unmatched book lines in the
 *               window, same direction (BK-03, one-to-many; many-to-one is a later step)
 *  - BANK_ONLY / BOOKS_ONLY leftovers (BK-04), stale books items highlighted (BK-05)
 *
 * A suggested match is classification, not sign-off (BKR-006): the unexplained closing
 * difference is reported and must reach zero before the reconciliation can be relied on.
 */
@Service
public class BankReconciliationService {

    private static final int DATE_TOLERANCE_DAYS = 3;
    private static final int STALE_DAYS = 30;
    private static final long MATERIAL_BANK_ONLY_PAISE = 50_000_00L;

    public record ReconcileResult(UUID reconciliationId, Map<MatchType, Integer> counts,
                                  long statementNetPaise, long ledgerNetPaise, long unexplainedPaise,
                                  int exceptionsCreated, int skippedExisting) {}

    private final EngagementRepository engagements;
    private final BankStatementLineRepository statements;
    private final BankLedgerLineRepository ledgerRepo;
    private final BankMatchResultRepository results;
    private final BankManualMatchRepository manualMatches;
    private final ExceptionService exceptionService;

    public BankReconciliationService(EngagementRepository engagements,
                                     BankStatementLineRepository statements,
                                     BankLedgerLineRepository ledgerRepo,
                                     BankMatchResultRepository results,
                                     BankManualMatchRepository manualMatches,
                                     ExceptionService exceptionService) {
        this.manualMatches = manualMatches;
        this.engagements = engagements;
        this.statements = statements;
        this.ledgerRepo = ledgerRepo;
        this.results = results;
        this.exceptionService = exceptionService;
    }

    @Transactional
    public ReconcileResult reconcile(UUID engagementId) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));
        List<BankStatementLine> stmt = statements.findByEngagementIdOrderByDateAsc(engagementId);
        List<BankLedgerLine> ledger = ledgerRepo.findByEngagementIdOrderByDateAsc(engagementId);

        UUID reconciliationId = UUID.randomUUID();
        results.deleteByEngagementId(engagementId);

        Map<MatchType, Integer> counts = new EnumMap<>(MatchType.class);
        for (MatchType t : MatchType.values()) counts.put(t, 0);
        List<BankMatchResult> out = new ArrayList<>();
        Set<Long> usedStmt = new HashSet<>();
        Set<Long> usedLedger = new HashSet<>();

        // pass 0: BKR-003 manual matches — the reviewer's pairing wins outright
        for (BankManualMatch m : manualMatches.findByEngagementIdOrderByDecidedAtDesc(engagementId)) {
            List<BankStatementLine> stmtHits = stmt.stream()
                    .filter(s -> !usedStmt.contains(s.getId()) && s.getReference().equals(m.getStatementReference()))
                    .toList();
            List<BankLedgerLine> ledgerHits = ledger.stream()
                    .filter(l -> !usedLedger.contains(l.getId()) && l.getVoucherId().equals(m.getVoucherId()))
                    .toList();
            if (stmtHits.isEmpty() || ledgerHits.isEmpty()) continue;
            stmtHits.forEach(s -> usedStmt.add(s.getId()));
            ledgerHits.forEach(l -> usedLedger.add(l.getId()));
            BankStatementLine s0 = stmtHits.get(0);
            counts.merge(MatchType.MANUAL, stmtHits.size(), Integer::sum);
            out.add(new BankMatchResult(engagementId, reconciliationId, MatchType.MANUAL, s0.getDate(),
                    s0.getReference(), s0.getNarration() + " [manual: " + m.getReason() + " — " + m.getDecidedBy() + "]",
                    stmtHits.stream().mapToLong(BankStatementLine::amountPaise).sum(), s0.isOutflow(),
                    m.getVoucherId(), null));
        }

        // index book lines by reference
        Map<String, List<BankLedgerLine>> ledgerByRef = new HashMap<>();
        for (BankLedgerLine l : ledger) ledgerByRef.computeIfAbsent(l.getReference(), k -> new ArrayList<>()).add(l);

        // pass 1: reference matches — exact date, then tolerance (BK-01/BK-02)
        for (BankStatementLine s : stmt) {
            List<BankLedgerLine> candidates = ledgerByRef.getOrDefault(s.getReference(), List.of());
            BankLedgerLine best = null;
            long bestGap = Long.MAX_VALUE;
            for (BankLedgerLine l : candidates) {
                if (usedLedger.contains(l.getId())) continue;
                if (l.amountPaise() != s.amountPaise() || l.isOutflow() != s.isOutflow()) continue;
                long gap = Math.abs(ChronoUnit.DAYS.between(l.getDate(), s.getDate()));
                if (gap < bestGap) { best = l; bestGap = gap; }
            }
            if (best != null) {
                usedStmt.add(s.getId());
                usedLedger.add(best.getId());
                MatchType type = bestGap == 0 ? MatchType.EXACT : MatchType.TOLERANCE;
                counts.merge(type, 1, Integer::sum);
                out.add(new BankMatchResult(engagementId, reconciliationId, type, s.getDate(), s.getReference(),
                        s.getNarration(), s.amountPaise(), s.isOutflow(), best.getVoucherId(), (int) bestGap));
            }
        }

        // pass 2: grouped one-to-many (BK-03) — statement line = sum of unmatched book lines in window
        for (BankStatementLine s : stmt) {
            if (usedStmt.contains(s.getId())) continue;
            List<BankLedgerLine> window = ledger.stream()
                    .filter(l -> !usedLedger.contains(l.getId()))
                    .filter(l -> l.isOutflow() == s.isOutflow())
                    .filter(l -> Math.abs(ChronoUnit.DAYS.between(l.getDate(), s.getDate())) <= DATE_TOLERANCE_DAYS)
                    .toList();
            long total = window.stream().mapToLong(BankLedgerLine::amountPaise).sum();
            if (window.size() >= 2 && total == s.amountPaise()) {
                usedStmt.add(s.getId());
                window.forEach(l -> usedLedger.add(l.getId()));
                counts.merge(MatchType.GROUPED, 1, Integer::sum);
                out.add(new BankMatchResult(engagementId, reconciliationId, MatchType.GROUPED, s.getDate(),
                        s.getReference(), s.getNarration() + " = " + window.size() + " book entries",
                        s.amountPaise(), s.isOutflow(),
                        window.stream().map(BankLedgerLine::getVoucherId).sorted()
                                .collect(Collectors.joining(" ")), null));
            }
        }

        // pass 3: leftovers (BK-04/BK-05) -> findings via the shared exception path
        List<Finding> findings = new ArrayList<>();
        long statementNet = 0;
        long bankOnlyNet = 0;
        for (BankStatementLine s : stmt) {
            statementNet += s.getCreditPaise() - s.getDebitPaise();
            if (usedStmt.contains(s.getId())) continue;
            counts.merge(MatchType.BANK_ONLY, 1, Integer::sum);
            bankOnlyNet += s.getCreditPaise() - s.getDebitPaise();
            out.add(new BankMatchResult(engagementId, reconciliationId, MatchType.BANK_ONLY, s.getDate(),
                    s.getReference(), s.getNarration(), s.amountPaise(), s.isOutflow(), "", null));
            findings.add(new Finding("BK-04B", "Bank-only item (no book entry)",
                    s.amountPaise() >= MATERIAL_BANK_ONLY_PAISE ? Finding.Severity.MEDIUM : Finding.Severity.LOW,
                    s.amountPaise(),
                    "Bank statement " + s.getDate() + " \"" + s.getNarration() + "\" Rs " + rupees(s.amountPaise())
                            + " (" + (s.isOutflow() ? "debit" : "credit") + ") has no matching book entry.",
                    List.of("BANK:" + s.getReference()),
                    s.getSourceFile() + ":" + s.getSourceRow()));
        }
        long ledgerNet = 0;
        long booksOnlyNet = 0;
        for (BankLedgerLine l : ledger) {
            ledgerNet += l.getDebitPaise() - l.getCreditPaise();
            if (usedLedger.contains(l.getId())) continue;
            counts.merge(MatchType.BOOKS_ONLY, 1, Integer::sum);
            booksOnlyNet += l.getDebitPaise() - l.getCreditPaise();
            boolean stale = ChronoUnit.DAYS.between(l.getDate(), engagement.getCloseDate()) > STALE_DAYS;
            out.add(new BankMatchResult(engagementId, reconciliationId, MatchType.BOOKS_ONLY, l.getDate(),
                    l.getReference(), l.getNarration() == null ? "" : l.getNarration(),
                    l.amountPaise(), l.isOutflow(), l.getVoucherId(), null));
            findings.add(new Finding("BK-04L", "Books-only item (never reached the bank)", Finding.Severity.MEDIUM,
                    l.amountPaise(),
                    "Book entry " + l.getVoucherId() + " dated " + l.getDate() + " ref " + l.getReference()
                            + " Rs " + rupees(l.amountPaise()) + " never appeared in the bank statement"
                            + (stale ? " [stale: more than " + STALE_DAYS + " days before close]" : "") + ".",
                    List.of(l.getVoucherId()),
                    l.getSourceFile() + ":" + l.getSourceRow()));
        }
        results.saveAll(out);

        // BKR-006: report the unexplained closing difference after recognised reconciling items
        long unexplained = (statementNet - bankOnlyNet) - (ledgerNet - booksOnlyNet);

        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, reconciliationId, findings);
        return new ReconcileResult(reconciliationId, counts, statementNet, ledgerNet, unexplained,
                raised.created().size(), raised.skipped());
    }

    /** BKR-003: record a manual statement↔voucher pairing with a documented reason. */
    @Transactional
    public BankManualMatch manualLink(UUID engagementId, String statementReference, String voucherId,
                                      String reason, String decidedBy) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A documented reason is required for a manual match (BKR-003).");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy is required.");
        }
        boolean stmtExists = statements.findByEngagementIdOrderByDateAsc(engagementId).stream()
                .anyMatch(s -> s.getReference().equals(statementReference));
        boolean voucherExists = ledgerRepo.findByEngagementIdOrderByDateAsc(engagementId).stream()
                .anyMatch(l -> l.getVoucherId().equals(voucherId));
        if (!stmtExists) throw new IllegalArgumentException("Statement reference not found: " + statementReference);
        if (!voucherExists) throw new IllegalArgumentException("Book voucher not found: " + voucherId);
        BankManualMatch m = new BankManualMatch(UUID.randomUUID(), engagementId,
                statementReference.trim(), voucherId.trim(), reason.trim(), decidedBy.trim(),
                java.time.Instant.now());
        manualMatches.save(m);
        return m;
    }

    public List<BankManualMatch> manualLinks(UUID engagementId) {
        return manualMatches.findByEngagementIdOrderByDecidedAtDesc(engagementId);
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
