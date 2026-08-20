package com.ledgerintegrity.platform.dashboard;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.Voucher;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BRD §18.1 — the firm portfolio dashboard ("which engagements have high-risk open
 * items, overdue evidence or incomplete sign-off?") and the risk explorer ("which
 * transactions, vendors, users, accounts and periods drive risk?").
 * Estimated exposure and confirmed misstatement are kept apart (RSK-005).
 */
@Service
public class DashboardService {

    public record PortfolioRow(String engagementId, String clientName, LocalDate fyStart, LocalDate fyEnd,
                               LocalDate closeDate, long populationCount,
                               int openExceptions, int openHigh, int confirmedExceptions,
                               long openCases, long totalCases, int overdueEvidence,
                               long estimatedExposurePaise, long confirmedExposurePaise,
                               String workpaperStatus) {}

    public record Slice(String key, int count, int highCount, long exposurePaise) {}

    public record Explorer(List<Slice> byRule, List<Slice> byUser, List<Slice> byMonth, List<Slice> byAccount) {}

    private final EngagementRepository engagements;
    private final LedgerEntryRepository entries;
    private final ExceptionCaseRepository exceptions;
    private final InvestigationCaseRepository cases;
    private final EvidenceRequestRepository evidence;
    private final WorkpaperRepository workpapers;

    public DashboardService(EngagementRepository engagements,
                            LedgerEntryRepository entries,
                            ExceptionCaseRepository exceptions,
                            InvestigationCaseRepository cases,
                            EvidenceRequestRepository evidence,
                            WorkpaperRepository workpapers) {
        this.engagements = engagements;
        this.entries = entries;
        this.exceptions = exceptions;
        this.cases = cases;
        this.evidence = evidence;
        this.workpapers = workpapers;
    }

    public List<PortfolioRow> portfolio(UUID firmId) {
        List<PortfolioRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Engagement e : engagements.findByFirmIdOrderByCreatedAtDesc(firmId)) {
            List<ExceptionCase> all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId());
            int openCount = 0, openHigh = 0, confirmed = 0;
            // Exposure de-duplicates like case consolidation does: signals on the same case
            // reference the same vouchers, so a case contributes its LARGEST member exposure,
            // not the sum. Statistical signals (Benford) carry no rupee exposure at all.
            Map<UUID, Long> estByCase = new HashMap<>(), confByCase = new HashMap<>();
            long estUncased = 0, confUncased = 0;
            for (ExceptionCase x : all) {
                long expo = monetaryExposure(x);
                if (isOpen(x)) {
                    openCount++;
                    if (x.getCaseId() == null) estUncased += expo;
                    else estByCase.merge(x.getCaseId(), expo, Math::max);
                    if (x.getSeverity() == com.ledgerintegrity.platform.rules.Finding.Severity.HIGH) openHigh++;
                } else if (x.getStatus() == ExceptionCase.Status.CONFIRMED) {
                    confirmed++;
                    if (x.getCaseId() == null) confUncased += expo;
                    else confByCase.merge(x.getCaseId(), expo, Math::max);
                }
            }
            long estimated = estUncased + estByCase.values().stream().mapToLong(Long::longValue).sum();
            long confirmedExposure = confUncased + confByCase.values().stream().mapToLong(Long::longValue).sum();
            // open cases = cases with at least one open member
            Map<UUID, Boolean> caseOpen = new HashMap<>();
            for (ExceptionCase x : all) {
                if (x.getCaseId() == null) continue;
                caseOpen.merge(x.getCaseId(), isOpen(x), (a, b) -> a || b);
            }
            long openCases = caseOpen.values().stream().filter(Boolean::booleanValue).count();

            int overdue = (int) evidence.findByEngagementIdOrderByCreatedAtDesc(e.getId()).stream()
                    .filter(r -> r.isOverdue(today))
                    .count();
            String wpStatus = workpapers.findByEngagementIdOrderByVersionDesc(e.getId()).stream()
                    .findFirst().map(w -> "v" + w.getVersion() + " " + w.getStatus()).orElse("none");

            rows.add(new PortfolioRow(e.getId().toString(), e.getClientName(), e.getFyStart(), e.getFyEnd(),
                    e.getCloseDate(), entries.countByEngagementId(e.getId()),
                    openCount, openHigh, confirmed,
                    openCases, cases.countByEngagementId(e.getId()), overdue,
                    estimated, confirmedExposure, wpStatus));
        }
        rows.sort(Comparator.comparingInt((PortfolioRow r) -> r.openHigh()).reversed()
                .thenComparing(Comparator.comparingLong(PortfolioRow::estimatedExposurePaise).reversed()));
        return rows;
    }

    public Explorer explorer(UUID engagementId) {
        List<LedgerRow> population = entries.findByEngagementId(engagementId).stream()
                .map(LedgerEntry::toRow).toList();
        Map<String, Voucher> voucherById = new HashMap<>();
        for (Voucher v : Voucher.group(population)) voucherById.put(v.id(), v);

        Map<String, long[]> byRule = new LinkedHashMap<>();    // [count, high, exposure]
        Map<String, long[]> byUser = new LinkedHashMap<>();
        Map<String, long[]> byMonth = new LinkedHashMap<>();
        Map<String, long[]> byAccount = new LinkedHashMap<>();

        for (ExceptionCase x : exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(engagementId)) {
            if (!isOpen(x)) continue; // the explorer answers "what drives risk NOW"
            boolean high = x.getSeverity() == com.ledgerintegrity.platform.rules.Finding.Severity.HIGH;
            add(byRule, x.getRuleId() + " " + x.getRuleName(), high, monetaryExposure(x));

            Voucher v = firstVoucher(x, voucherById);
            add(byUser, v == null || v.userId() == null ? "(no user data)" : v.userId(), high, monetaryExposure(x));
            add(byMonth, v == null || v.txnDate() == null ? "(n/a)" : v.txnDate().toString().substring(0, 7),
                    high, monetaryExposure(x));
            String account = "(n/a)";
            if (v != null) {
                for (LedgerRow line : v.lines()) {
                    if (line.debit() != null) { account = line.accountCode() + " " + line.accountName(); break; }
                }
            }
            add(byAccount, account, high, monetaryExposure(x));
        }
        return new Explorer(slices(byRule), slices(byUser), slices(byMonth), slices(byAccount));
    }

    // ---------- helpers ----------

    /** Benford is a statistical review signal; it never contributes rupee exposure (RSK-005). */
    private static long monetaryExposure(ExceptionCase x) {
        return x.getRuleId() != null && x.getRuleId().startsWith("BEN") ? 0 : x.getExposurePaise();
    }

    private static boolean isOpen(ExceptionCase x) {
        return switch (x.getStatus()) {
            case NEW, UNDER_REVIEW, INFO_REQUIRED -> true;
            default -> false;
        };
    }

    private static Voucher firstVoucher(ExceptionCase x, Map<String, Voucher> voucherById) {
        for (String token : x.getVoucherIds().split(" ")) {
            Voucher v = voucherById.get(token);
            if (v != null) return v;
        }
        return null;
    }

    private static void add(Map<String, long[]> map, String key, boolean high, long exposure) {
        long[] agg = map.computeIfAbsent(key, k -> new long[3]);
        agg[0]++;
        if (high) agg[1]++;
        agg[2] += exposure;
    }

    private static List<Slice> slices(Map<String, long[]> map) {
        return map.entrySet().stream()
                .map(e -> new Slice(e.getKey(), (int) e.getValue()[0], (int) e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong((Slice s) -> s.exposurePaise()).reversed())
                .limit(15)
                .toList();
    }
}
