package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side;
import com.ledgerintegrity.platform.gst.persist.GstManualMatch;
import com.ledgerintegrity.platform.gst.persist.GstManualMatchRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr1Invoice;
import com.ledgerintegrity.platform.gst.persist.Gstr1InvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr2bInvoice;
import com.ledgerintegrity.platform.gst.persist.Gstr2bInvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr3bSummary;
import com.ledgerintegrity.platform.gst.persist.Gstr3bSummaryRepository;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.SalesInvoice;
import com.ledgerintegrity.platform.gst.persist.SalesInvoiceRepository;
import com.ledgerintegrity.platform.rules.ExceptionService;
import com.ledgerintegrity.platform.rules.Finding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GS-01: purchase register vs GSTR-2B, classified into clear business categories
 * (GST-003). Calculates potential ITC exposure but leaves legal eligibility to the
 * professional (GST-004) — exception wording stays neutral.
 *
 * Match key (MVP): exact GSTIN + invoice number; value comparison at 1 rupee tolerance.
 * Fuzzy matching with confidence scores (GST-002) is a later increment.
 */
@Service
public class GstReconciliationService {

    /** 1 rupee — differences at or below this are treated as rounding, not mismatch. */
    private static final long VALUE_TOLERANCE_PAISE = 100;

    public record ReconcileResult(UUID reconciliationId, Map<Category, Integer> counts,
                                  long itcExposurePaise, int exceptionsCreated, int skippedExisting) {}

    private final PurchaseInvoiceRepository purchases;
    private final Gstr2bInvoiceRepository g2bRepo;
    private final SalesInvoiceRepository salesRepo;
    private final Gstr1InvoiceRepository g1Repo;
    private final Gstr3bSummaryRepository g3bRepo;
    private final GstMatchResultRepository results;
    private final GstManualMatchRepository manualMatches;
    private final ExceptionService exceptionService;

    public GstReconciliationService(PurchaseInvoiceRepository purchases,
                                    Gstr2bInvoiceRepository g2bRepo,
                                    SalesInvoiceRepository salesRepo,
                                    Gstr1InvoiceRepository g1Repo,
                                    Gstr3bSummaryRepository g3bRepo,
                                    GstMatchResultRepository results,
                                    GstManualMatchRepository manualMatches,
                                    ExceptionService exceptionService) {
        this.manualMatches = manualMatches;
        this.purchases = purchases;
        this.g2bRepo = g2bRepo;
        this.salesRepo = salesRepo;
        this.g1Repo = g1Repo;
        this.g3bRepo = g3bRepo;
        this.results = results;
        this.exceptionService = exceptionService;
    }

    @Transactional
    public ReconcileResult reconcile(UUID engagementId) {
        List<PurchaseInvoice> books = purchases.findByEngagementId(engagementId);
        List<Gstr2bInvoice> portal = g2bRepo.findByEngagementId(engagementId);

        Map<String, Gstr2bInvoice> portalByKey = new LinkedHashMap<>();
        for (Gstr2bInvoice g : portal) portalByKey.put(key(g.getSupplierGstin(), g.getInvoiceNo()), g);

        // GST-007: apply recorded manual links (books key -> portal key)
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> portalConsumed = new HashSet<>();
        for (GstManualMatch m : manualMatches.findByEngagementIdAndSide(engagementId, Side.PURCHASE)) {
            String portalKey = key(m.getPortalGstin(), m.getPortalInvoiceNo());
            aliases.put(key(m.getBooksGstin(), m.getBooksInvoiceNo()), portalKey);
            portalConsumed.add(portalKey);
        }

        UUID reconciliationId = UUID.randomUUID();
        results.deleteBySide(engagementId, Side.PURCHASE, true); // derived data — rebuilt each run

        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Category c : Category.values()) counts.put(c, 0);
        long exposure = 0;
        List<GstMatchResult> matchRows = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Set<String> booksKeys = new HashSet<>();

        for (PurchaseInvoice p : books) {
            String k = key(p.getGstin(), p.getInvoiceNo());
            booksKeys.add(k);
            Gstr2bInvoice g = portalByKey.get(k);
            boolean manual = false;
            if (g == null && aliases.containsKey(k)) {
                g = portalByKey.get(aliases.get(k));
                manual = g != null;
            }
            if (g == null) {
                counts.merge(Category.BOOKS_ONLY, 1, Integer::sum);
                exposure += p.getTaxPaise();
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.PURCHASE, Category.BOOKS_ONLY,
                        p.getGstin(), p.getInvoiceNo(), p.getVendorName(),
                        p.getTaxablePaise(), p.getTaxPaise(), null, null,
                        p.getTaxPaise(), p.getVoucherId()));
                matchRows.get(matchRows.size() - 1).setOwnGstin(p.getOwnGstin());
                findings.add(new Finding("GS-01B", "Books-only (not in GSTR-2B)", Finding.Severity.MEDIUM,
                        p.getTaxPaise(),
                        "Invoice " + p.getInvoiceNo() + " from " + p.getVendorName()
                                + " (taxable Rs " + rupees(p.getTaxablePaise()) + ") is in the purchase register but absent from GSTR-2B."
                                + " Potential ITC at stake Rs " + rupees(p.getTaxPaise())
                                + " — eligibility is a professional decision.",
                        List.of(voucherToken(p)),
                        p.getSourceFile() + ":" + p.getSourceRow()));
            } else if (Math.abs(g.getTaxablePaise() - p.getTaxablePaise()) > VALUE_TOLERANCE_PAISE) {
                long diff = Math.abs(g.getTaxPaise() - p.getTaxPaise());
                counts.merge(Category.VALUE_MISMATCH, 1, Integer::sum);
                exposure += diff;
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.PURCHASE, Category.VALUE_MISMATCH,
                        p.getGstin(), p.getInvoiceNo(), p.getVendorName(),
                        p.getTaxablePaise(), p.getTaxPaise(), g.getTaxablePaise(), g.getTaxPaise(),
                        diff, p.getVoucherId()));
                matchRows.get(matchRows.size() - 1).setOwnGstin(p.getOwnGstin());
                if (manual) matchRows.get(matchRows.size() - 1).markManuallyLinked();
                findings.add(new Finding("GS-01V", "Value mismatch books vs GSTR-2B", Finding.Severity.MEDIUM,
                        diff,
                        "Invoice " + p.getInvoiceNo() + " (" + p.getVendorName() + "): books taxable Rs "
                                + rupees(p.getTaxablePaise()) + " vs GSTR-2B Rs " + rupees(g.getTaxablePaise())
                                + ". Tax difference Rs " + rupees(diff) + ".",
                        List.of(voucherToken(p)),
                        p.getSourceFile() + ":" + p.getSourceRow() + " " + g.getSourceFile() + ":" + g.getSourceRow()));
            } else {
                counts.merge(Category.MATCHED, 1, Integer::sum);
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.PURCHASE, Category.MATCHED,
                        p.getGstin(), p.getInvoiceNo(), p.getVendorName(),
                        p.getTaxablePaise(), p.getTaxPaise(), g.getTaxablePaise(), g.getTaxPaise(),
                        0, p.getVoucherId()));
                matchRows.get(matchRows.size() - 1).setOwnGstin(p.getOwnGstin());
                if (manual) matchRows.get(matchRows.size() - 1).markManuallyLinked();
            }
        }

        for (Gstr2bInvoice g : portal) {
            String portalKey = key(g.getSupplierGstin(), g.getInvoiceNo());
            if (booksKeys.contains(portalKey) || portalConsumed.contains(portalKey)) continue;
            counts.merge(Category.G2B_ONLY, 1, Integer::sum);
            exposure += g.getTaxPaise();
            matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.PURCHASE, Category.G2B_ONLY,
                    g.getSupplierGstin(), g.getInvoiceNo(), g.getSupplierName(),
                    null, null, g.getTaxablePaise(), g.getTaxPaise(),
                    g.getTaxPaise(), null));
            findings.add(new Finding("GS-01G", "GSTR-2B-only (not in books)", Finding.Severity.MEDIUM,
                    g.getTaxPaise(),
                    "Supplier " + g.getSupplierName() + " filed invoice " + g.getInvoiceNo() + " ("
                            + g.getInvoiceDate() + ", taxable Rs " + rupees(g.getTaxablePaise())
                            + ") in GSTR-2B but it is not booked — possible unrecorded purchase or supplier error.",
                    List.of("2B:" + g.getSupplierGstin() + "/" + g.getInvoiceNo()),
                    g.getSourceFile() + ":" + g.getSourceRow()));
        }
        appendSuggestions(matchRows, engagementId, reconciliationId, Side.PURCHASE, counts);
        results.saveAll(matchRows);

        // findings -> exceptions through the shared, idempotent write-path
        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, reconciliationId, findings);
        return new ReconcileResult(reconciliationId, counts, exposure, raised.created().size(), raised.skipped());
    }

    /**
     * GS-02: sales register vs GSTR-1 — outward supplies reported completely and accurately.
     * BOOKS_ONLY = booked sale missing from GSTR-1 (possible under-reported liability);
     * G2B_ONLY  = reported in GSTR-1 but not booked (possible unrecorded sale or error).
     */
    @Transactional
    public ReconcileResult reconcileSales(UUID engagementId) {
        List<SalesInvoice> books = salesRepo.findByEngagementId(engagementId);
        List<Gstr1Invoice> portal = g1Repo.findByEngagementId(engagementId);

        Map<String, Gstr1Invoice> portalByKey = new LinkedHashMap<>();
        for (Gstr1Invoice g : portal) portalByKey.put(key(g.getCustomerGstin(), g.getInvoiceNo()), g);

        // GST-007: apply recorded manual links (books key -> portal key)
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> portalConsumed = new HashSet<>();
        for (GstManualMatch m : manualMatches.findByEngagementIdAndSide(engagementId, Side.SALES)) {
            String portalKey = key(m.getPortalGstin(), m.getPortalInvoiceNo());
            aliases.put(key(m.getBooksGstin(), m.getBooksInvoiceNo()), portalKey);
            portalConsumed.add(portalKey);
        }

        UUID reconciliationId = UUID.randomUUID();
        results.deleteBySide(engagementId, Side.SALES, false);

        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Category c : Category.values()) counts.put(c, 0);
        long exposure = 0;
        List<GstMatchResult> matchRows = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Set<String> booksKeys = new HashSet<>();

        for (SalesInvoice s : books) {
            String k = key(s.getGstin(), s.getInvoiceNo());
            booksKeys.add(k);
            Gstr1Invoice g = portalByKey.get(k);
            boolean manual = false;
            if (g == null && aliases.containsKey(k)) {
                g = portalByKey.get(aliases.get(k));
                manual = g != null;
            }
            if (g == null) {
                counts.merge(Category.BOOKS_ONLY, 1, Integer::sum);
                exposure += s.getTaxPaise();
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.SALES, Category.BOOKS_ONLY,
                        s.getGstin(), s.getInvoiceNo(), s.getCustomerName(),
                        s.getTaxablePaise(), s.getTaxPaise(), null, null,
                        s.getTaxPaise(), s.getVoucherId()));
                findings.add(new Finding("GS-02B", "Sales invoice not reported in GSTR-1", Finding.Severity.MEDIUM,
                        s.getTaxPaise(),
                        "Invoice " + s.getInvoiceNo() + " to " + s.getCustomerName()
                                + " (taxable Rs " + rupees(s.getTaxablePaise())
                                + ") is in the sales register but absent from GSTR-1 — possible under-reported"
                                + " output liability of Rs " + rupees(s.getTaxPaise())
                                + ". Filing decisions remain professional judgements.",
                        List.of(salesToken(s)),
                        s.getSourceFile() + ":" + s.getSourceRow()));
            } else if (Math.abs(g.getTaxablePaise() - s.getTaxablePaise()) > VALUE_TOLERANCE_PAISE) {
                long diff = Math.abs(g.getTaxPaise() - s.getTaxPaise());
                counts.merge(Category.VALUE_MISMATCH, 1, Integer::sum);
                exposure += diff;
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.SALES, Category.VALUE_MISMATCH,
                        s.getGstin(), s.getInvoiceNo(), s.getCustomerName(),
                        s.getTaxablePaise(), s.getTaxPaise(), g.getTaxablePaise(), g.getTaxPaise(),
                        diff, s.getVoucherId()));
                if (manual) matchRows.get(matchRows.size() - 1).markManuallyLinked();
                findings.add(new Finding("GS-02V", "Value mismatch books vs GSTR-1", Finding.Severity.MEDIUM,
                        diff,
                        "Invoice " + s.getInvoiceNo() + " (" + s.getCustomerName() + "): books taxable Rs "
                                + rupees(s.getTaxablePaise()) + " vs GSTR-1 Rs " + rupees(g.getTaxablePaise())
                                + ". Tax difference Rs " + rupees(diff) + ".",
                        List.of(salesToken(s)),
                        s.getSourceFile() + ":" + s.getSourceRow() + " " + g.getSourceFile() + ":" + g.getSourceRow()));
            } else {
                counts.merge(Category.MATCHED, 1, Integer::sum);
                matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.SALES, Category.MATCHED,
                        s.getGstin(), s.getInvoiceNo(), s.getCustomerName(),
                        s.getTaxablePaise(), s.getTaxPaise(), g.getTaxablePaise(), g.getTaxPaise(),
                        0, s.getVoucherId()));
                if (manual) matchRows.get(matchRows.size() - 1).markManuallyLinked();
            }
        }

        for (Gstr1Invoice g : portal) {
            String portalKey = key(g.getCustomerGstin(), g.getInvoiceNo());
            if (booksKeys.contains(portalKey) || portalConsumed.contains(portalKey)) continue;
            counts.merge(Category.G2B_ONLY, 1, Integer::sum);
            exposure += g.getTaxPaise();
            matchRows.add(new GstMatchResult(engagementId, reconciliationId, Side.SALES, Category.G2B_ONLY,
                    g.getCustomerGstin(), g.getInvoiceNo(), g.getCustomerName(),
                    null, null, g.getTaxablePaise(), g.getTaxPaise(),
                    g.getTaxPaise(), null));
            findings.add(new Finding("GS-02G", "Reported in GSTR-1 but not in books", Finding.Severity.MEDIUM,
                    g.getTaxPaise(),
                    "Invoice " + g.getInvoiceNo() + " to " + g.getCustomerName() + " (" + g.getInvoiceDate()
                            + ", taxable Rs " + rupees(g.getTaxablePaise())
                            + ") appears in GSTR-1 but not in the sales register — possible unrecorded sale or filing error.",
                    List.of("G1:" + g.getCustomerGstin() + "/" + g.getInvoiceNo()),
                    g.getSourceFile() + ":" + g.getSourceRow()));
        }
        appendSuggestions(matchRows, engagementId, reconciliationId, Side.SALES, counts);
        results.saveAll(matchRows);

        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, reconciliationId, findings);
        return new ReconcileResult(reconciliationId, counts, exposure, raised.created().size(), raised.skipped());
    }

    public record PeriodComparison(String period, long gstr1TaxablePaise, long gstr1TaxPaise,
                                   Long declaredTaxablePaise, Long declaredTaxPaise, long taxDiffPaise) {}

    public record Gstr3bResult(List<PeriodComparison> periods, int differences,
                               long totalTaxDiffPaise, int exceptionsCreated, int skippedExisting) {}

    /** GS-03 / GST-005: GSTR-1 detail aggregated by tax period vs the GSTR-3B declared summary. */
    @Transactional
    public Gstr3bResult reconcile3b(UUID engagementId) {
        Map<String, long[]> g1ByPeriod = new java.util.TreeMap<>();
        for (Gstr1Invoice g : g1Repo.findByEngagementId(engagementId)) {
            String period = g.getInvoiceDate().toString().substring(0, 7);
            long[] agg = g1ByPeriod.computeIfAbsent(period, k -> new long[2]);
            agg[0] += g.getTaxablePaise();
            agg[1] += g.getTaxPaise();
        }
        Map<String, Gstr3bSummary> declared = new LinkedHashMap<>();
        for (Gstr3bSummary s : g3bRepo.findByEngagementIdOrderByPeriodAsc(engagementId)) {
            declared.put(s.getPeriod(), s);
        }

        List<PeriodComparison> periods = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int differences = 0;
        long totalDiff = 0;
        UUID reconciliationId = UUID.randomUUID();
        for (Map.Entry<String, long[]> e : g1ByPeriod.entrySet()) {
            Gstr3bSummary d = declared.get(e.getKey());
            long diff = d == null ? e.getValue()[1] : Math.abs(e.getValue()[1] - d.getTaxPaise());
            periods.add(new PeriodComparison(e.getKey(), e.getValue()[0], e.getValue()[1],
                    d == null ? null : d.getTaxablePaise(), d == null ? null : d.getTaxPaise(), diff));
            if (diff > VALUE_TOLERANCE_PAISE) {
                differences++;
                totalDiff += diff;
                findings.add(new Finding("GS-03", "GSTR-1 vs GSTR-3B period difference", Finding.Severity.MEDIUM,
                        diff,
                        "Tax period " + e.getKey() + ": GSTR-1 detail totals Rs " + rupees(e.getValue()[1])
                                + " of tax but GSTR-3B declares "
                                + (d == null ? "no summary for the period" : "Rs " + rupees(d.getTaxPaise()))
                                + " — unresolved difference Rs " + rupees(diff)
                                + ". The correction and filing decision remain professional judgements.",
                        List.of("3B:" + e.getKey()),
                        d == null ? "gstr3b:missing" : d.getSourceFile() + ":" + d.getSourceRow()));
            }
        }
        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, reconciliationId, findings);
        return new Gstr3bResult(periods, differences, totalDiff, raised.created().size(), raised.skipped());
    }

    /**
     * GST-002: fuzzy suggestions — same-GSTIN unmatched pairs scored by invoice-number
     * similarity and amount proximity. A suggestion is never a match: it waits for a
     * GST-007 manual link (approve) or is simply ignored (reject). Confidence and the
     * matched fields are shown with every suggestion.
     */
    private void appendSuggestions(List<GstMatchResult> matchRows, UUID engagementId,
                                   UUID reconciliationId, Side side, Map<Category, Integer> counts) {
        List<GstMatchResult> booksOnly = matchRows.stream()
                .filter(m -> m.getCategory() == Category.BOOKS_ONLY).toList();
        List<GstMatchResult> portalOnly = matchRows.stream()
                .filter(m -> m.getCategory() == Category.G2B_ONLY).toList();
        List<GstMatchResult> suggestions = new ArrayList<>();
        for (GstMatchResult b : booksOnly) {
            GstMatchResult best = null;
            double bestScore = 0;
            for (GstMatchResult g : portalOnly) {
                if (!b.getGstin().equalsIgnoreCase(g.getGstin())) continue; // same supplier/customer only
                double sim = similarity(b.getInvoiceNo(), g.getInvoiceNo());
                long bt = b.getBooksTaxablePaise() == null ? 0 : b.getBooksTaxablePaise();
                long gt = g.getG2bTaxablePaise() == null ? 0 : g.getG2bTaxablePaise();
                double amt = (bt == 0 || gt == 0) ? 0 : (double) Math.min(bt, gt) / Math.max(bt, gt);
                double score = 0.3 + 0.4 * sim + 0.3 * amt; // 0.3 = same GSTIN
                if (score > bestScore) { bestScore = score; best = g; }
            }
            if (best == null || bestScore < 0.65) continue;
            GstMatchResult s = new GstMatchResult(engagementId, reconciliationId, side, Category.SUGGESTED,
                    b.getGstin(), b.getInvoiceNo(), b.getPartyName(),
                    b.getBooksTaxablePaise(), b.getBooksTaxPaise(),
                    best.getG2bTaxablePaise(), best.getG2bTaxPaise(),
                    Math.abs((b.getBooksTaxPaise() == null ? 0 : b.getBooksTaxPaise())
                            - (best.getG2bTaxPaise() == null ? 0 : best.getG2bTaxPaise())),
                    b.getVoucherId());
            s.setOwnGstin(b.getOwnGstin());
            s.setSuggestion(Math.round(bestScore * 100) / 100.0,
                    "portal=" + best.getInvoiceNo() + "; gstin; invoice "
                            + Math.round(similarity(b.getInvoiceNo(), best.getInvoiceNo()) * 100) + "%; amount");
            suggestions.add(s);
            counts.merge(Category.SUGGESTED, 1, Integer::sum);
        }
        matchRows.addAll(suggestions);
    }

    /** GST-009: per-registration view — counts by category for each of the entity's own GSTINs. */
    public List<Map<String, Object>> registrationSummary(UUID engagementId) {
        Map<String, Map<String, long[]>> byRegistration = new java.util.TreeMap<>();
        for (Side side : Side.values()) {
            for (GstMatchResult m : results.findBySide(engagementId, side, side == Side.PURCHASE)) {
                String reg = m.getOwnGstin() == null || m.getOwnGstin().isBlank()
                        ? "(single registration)" : m.getOwnGstin();
                long[] agg = byRegistration
                        .computeIfAbsent(reg, k -> new java.util.TreeMap<>())
                        .computeIfAbsent(side + ":" + m.getCategory(), k -> new long[2]);
                agg[0]++;
                if (m.getCategory() != Category.MATCHED) agg[1] += m.getTaxDiffPaise();
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byRegistration.forEach((reg, cats) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("ownGstin", reg);
            long exposure = 0;
            for (Map.Entry<String, long[]> e : cats.entrySet()) {
                row.put(e.getKey(), e.getValue()[0]);
                exposure += e.getValue()[1];
            }
            row.put("taxAtStakePaise", exposure);
            out.add(row);
        });
        return out;
    }

    /** Normalised Levenshtein similarity on upper-cased invoice numbers. */
    static double similarity(String a, String b) {
        String x = a.toUpperCase(), y = b.toUpperCase();
        int[][] d = new int[x.length() + 1][y.length() + 1];
        for (int i = 0; i <= x.length(); i++) d[i][0] = i;
        for (int j = 0; j <= y.length(); j++) d[0][j] = j;
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        int max = Math.max(x.length(), y.length());
        return max == 0 ? 1.0 : 1.0 - (double) d[x.length()][y.length()] / max;
    }

    /**
     * GST-007: record a manual invoice link with a documented reason. The link is
     * durable, reviewable, and applied on the next reconciliation of that side.
     */
    @Transactional
    public GstManualMatch manualLink(UUID engagementId, Side side,
                                     String booksGstin, String booksInvoiceNo,
                                     String portalGstin, String portalInvoiceNo,
                                     String reason, String decidedBy) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A documented reason is required for a manual link (GST-007).");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy is required.");
        }
        boolean booksExists = side == Side.PURCHASE
                ? purchases.findByEngagementId(engagementId).stream()
                        .anyMatch(p -> key(p.getGstin(), p.getInvoiceNo()).equals(key(booksGstin, booksInvoiceNo)))
                : salesRepo.findByEngagementId(engagementId).stream()
                        .anyMatch(s -> key(s.getGstin(), s.getInvoiceNo()).equals(key(booksGstin, booksInvoiceNo)));
        boolean portalExists = side == Side.PURCHASE
                ? g2bRepo.findByEngagementId(engagementId).stream()
                        .anyMatch(g -> key(g.getSupplierGstin(), g.getInvoiceNo()).equals(key(portalGstin, portalInvoiceNo)))
                : g1Repo.findByEngagementId(engagementId).stream()
                        .anyMatch(g -> key(g.getCustomerGstin(), g.getInvoiceNo()).equals(key(portalGstin, portalInvoiceNo)));
        if (!booksExists) throw new IllegalArgumentException("Books invoice not found: " + booksGstin + "/" + booksInvoiceNo);
        if (!portalExists) throw new IllegalArgumentException("Portal invoice not found: " + portalGstin + "/" + portalInvoiceNo);

        GstManualMatch match = new GstManualMatch(UUID.randomUUID(), engagementId, side,
                booksGstin.trim(), booksInvoiceNo.trim(), portalGstin.trim(), portalInvoiceNo.trim(),
                reason.trim(), decidedBy.trim(), java.time.Instant.now());
        manualMatches.save(match);
        return match;
    }

    public record CorrectionRow(String side, String category, String reference, String invoiceOrPeriod,
                                String party, Long booksTaxablePaise, Long portalTaxablePaise,
                                Long booksTaxPaise, Long portalTaxPaise,
                                long taxEffectPaise, String suggestedAction) {}

    /**
     * GST-008: the pre-filing correction schedule — every unresolved difference with its
     * financial effect and a suggested action. Owner and due date are assigned by the
     * GST professional; the schedule never files anything by itself.
     */
    public List<CorrectionRow> correctionSchedule(UUID engagementId) {
        List<CorrectionRow> rows = new ArrayList<>();
        for (Side side : Side.values()) {
            for (GstMatchResult m : results.findBySide(engagementId, side, side == Side.PURCHASE)) {
                if (m.getCategory() == Category.MATCHED) continue;
                rows.add(new CorrectionRow(side.name(), m.getCategory().name(), m.getGstin(), m.getInvoiceNo(),
                        m.getPartyName(), m.getBooksTaxablePaise(), m.getG2bTaxablePaise(),
                        m.getBooksTaxPaise(), m.getG2bTaxPaise(),
                        m.getTaxDiffPaise(), suggestedAction(side, m.getCategory())));
            }
        }
        // GSTR-1 vs 3B period differences (computed fresh, no exceptions raised here)
        Map<String, long[]> g1ByPeriod = new java.util.TreeMap<>();
        for (Gstr1Invoice g : g1Repo.findByEngagementId(engagementId)) {
            long[] agg = g1ByPeriod.computeIfAbsent(g.getInvoiceDate().toString().substring(0, 7), k -> new long[2]);
            agg[0] += g.getTaxablePaise();
            agg[1] += g.getTaxPaise();
        }
        for (Map.Entry<String, long[]> e : g1ByPeriod.entrySet()) {
            Gstr3bSummary d = g3bRepo.findByEngagementIdAndPeriod(engagementId, e.getKey()).orElse(null);
            long diff = d == null ? e.getValue()[1] : Math.abs(e.getValue()[1] - d.getTaxPaise());
            if (diff <= VALUE_TOLERANCE_PAISE) continue;
            // QA P3: the figures DRIVING the difference are the period's tax totals -
            // GSTR-1 tax vs 3B declared tax - so they must appear in the row
            rows.add(new CorrectionRow("OUTWARD_SUMMARY", "GSTR1_VS_3B", e.getKey(), e.getKey(), "",
                    e.getValue()[0], d == null ? null : d.getTaxablePaise(),
                    e.getValue()[1], d == null ? null : d.getTaxPaise(), diff,
                    "Reconcile GSTR-3B for the period; discharge or adjust the differential after professional review."));
        }
        rows.sort((a, b) -> Long.compare(b.taxEffectPaise(), a.taxEffectPaise()));
        return rows;
    }

    private static String suggestedAction(Side side, Category category) {
        if (side == Side.PURCHASE) {
            return switch (category) {
                case BOOKS_ONLY -> "Follow up the supplier to report the invoice; hold/review the ITC claim until it appears in GSTR-2B.";
                case G2B_ONLY -> "Verify whether the purchase is unrecorded; book it or obtain supplier clarification.";
                case VALUE_MISMATCH -> "Agree the correct value with the supplier; amend books or await supplier amendment.";
                default -> "";
            };
        }
        return switch (category) {
            case BOOKS_ONLY -> "Report the invoice through a GSTR-1 amendment; review output-liability impact.";
            case G2B_ONLY -> "Verify whether the sale is unrecorded; book it or amend GSTR-1.";
            case VALUE_MISMATCH -> "Agree the correct value with the customer; amend GSTR-1 or books.";
            default -> "";
        };
    }

    /** GL voucher when the register links one; otherwise a stable invoice token. */
    private static String salesToken(SalesInvoice s) {
        return (s.getVoucherId() != null && !s.getVoucherId().isBlank())
                ? s.getVoucherId()
                : "SINV:" + s.getGstin() + "/" + s.getInvoiceNo();
    }

    private static String key(String gstin, String invoiceNo) {
        return gstin.trim().toUpperCase() + "|" + invoiceNo.trim().toUpperCase();
    }

    /** GL voucher when the register links one; otherwise a stable invoice token. */
    private static String voucherToken(PurchaseInvoice p) {
        return (p.getVoucherId() != null && !p.getVoucherId().isBlank())
                ? p.getVoucherId()
                : "INV:" + p.getGstin() + "/" + p.getInvoiceNo();
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
