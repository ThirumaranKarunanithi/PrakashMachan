package com.ledgerintegrity.platform.vendor;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoiceRepository;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;
import com.ledgerintegrity.platform.vendor.persist.VendorRecordRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Per-vendor risk scoring (BRD §8 extension). Every component is a capped,
 * explainable aggregate of facts the platform already holds — exception history,
 * GST compliance, master-data behaviour, spend concentration and amount patterns.
 * A score identifies WHERE to look; it is never a conclusion about a vendor.
 */
@Service
public class VendorRiskService {

    // component caps sum to 100, mirroring the family-cap philosophy of case scoring
    static final int CAP_EXCEPTIONS = 35;
    static final int CAP_GST = 25;
    static final int CAP_BEHAVIOUR = 20;
    static final int CAP_CONCENTRATION = 10;
    static final int CAP_PATTERNS = 10;

    public record VendorRisk(String vendorId, String name, String gstin,
                             int invoiceCount, long purchaseValuePaise, double spendSharePct,
                             long itcAtStakePaise, int score,
                             Map<String, Integer> components, List<String> notes) {}

    private final VendorRecordRepository vendors;
    private final PurchaseInvoiceRepository purchases;
    private final GstMatchResultRepository gstMatches;
    private final ExceptionCaseRepository exceptions;
    private final EngagementRepository engagements;

    public VendorRiskService(VendorRecordRepository vendors, PurchaseInvoiceRepository purchases,
                             GstMatchResultRepository gstMatches, ExceptionCaseRepository exceptions,
                             EngagementRepository engagements) {
        this.vendors = vendors;
        this.purchases = purchases;
        this.gstMatches = gstMatches;
        this.exceptions = exceptions;
        this.engagements = engagements;
    }

    public List<VendorRisk> report(UUID engagementId) {
        Engagement engagement = engagements.findById(engagementId).orElseThrow();
        List<VendorRecord> master = vendors.findByEngagementId(engagementId);
        List<PurchaseInvoice> invoices = purchases.findByEngagementId(engagementId);
        List<ExceptionCase> allExceptions =
                exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(engagementId);
        List<GstMatchResult> purchaseMatches =
                gstMatches.findBySide(engagementId, GstMatchResult.Side.PURCHASE, true);

        // vendor universe: master records plus purchase-register vendors without a master row
        Map<String, VendorRecord> masterById = new LinkedHashMap<>();
        master.forEach(v -> masterById.putIfAbsent(v.getVendorId(), v));
        Map<String, List<PurchaseInvoice>> invoicesByVendor = new TreeMap<>();
        for (PurchaseInvoice p : invoices) {
            String vid = p.getVendorId() == null || p.getVendorId().isBlank() ? p.getVendorName() : p.getVendorId();
            invoicesByVendor.computeIfAbsent(vid, k -> new ArrayList<>()).add(p);
        }
        master.forEach(v -> invoicesByVendor.computeIfAbsent(v.getVendorId(), k -> new ArrayList<>()));

        long totalSpend = invoices.stream().mapToLong(PurchaseInvoice::getTaxablePaise).sum();

        // shared bank accounts across the master
        Map<String, List<VendorRecord>> byBank = new HashMap<>();
        for (VendorRecord v : master) {
            if (v.getBankAccount() != null && !v.getBankAccount().isBlank()) {
                byBank.computeIfAbsent(v.getBankAccount(), k -> new ArrayList<>()).add(v);
            }
        }

        List<VendorRisk> out = new ArrayList<>();
        for (Map.Entry<String, List<PurchaseInvoice>> e : invoicesByVendor.entrySet()) {
            String vid = e.getKey();
            VendorRecord rec = masterById.get(vid);
            List<PurchaseInvoice> own = e.getValue();
            String name = rec != null ? rec.getName()
                    : own.isEmpty() ? vid : own.get(0).getVendorName();
            String gstin = rec != null && rec.getGstin() != null ? rec.getGstin()
                    : own.isEmpty() ? "" : own.get(0).getGstin();

            Map<String, Integer> components = new LinkedHashMap<>();
            List<String> notes = new ArrayList<>();

            // 1. exception history (vendor tokens on consolidated signals)
            String token = "VENDOR:" + vid;
            int exPoints = 0, exCount = 0;
            for (ExceptionCase x : allExceptions) {
                if (!(" " + x.getVoucherIds() + " ").contains(" " + token + " ")) continue;
                exCount++;
                exPoints += x.getSeverity() == Finding.Severity.HIGH ? 10
                        : x.getSeverity() == Finding.Severity.MEDIUM ? 5 : 2;
            }
            if (exCount > 0) {
                components.put("exceptions", Math.min(exPoints, CAP_EXCEPTIONS));
                notes.add(exCount + " exception signal(s) reference this vendor.");
            }

            // 2. GST compliance for the vendor's GSTIN
            long itcAtStake = 0;
            int gstPoints = 0, gstIssues = 0;
            if (!gstin.isBlank()) {
                for (GstMatchResult m : purchaseMatches) {
                    if (!gstin.equals(m.getGstin()) || m.getCategory() == GstMatchResult.Category.MATCHED) continue;
                    gstIssues++;
                    itcAtStake += m.getTaxDiffPaise();
                    gstPoints += switch (m.getCategory()) {
                        case BOOKS_ONLY -> 6;
                        case VALUE_MISMATCH -> 5;
                        case G2B_ONLY -> 3;
                        default -> 2;
                    };
                }
            }
            if (gstIssues > 0) {
                components.put("gstCompliance", Math.min(gstPoints, CAP_GST));
                notes.add(gstIssues + " unreconciled GST item(s); ITC at stake Rs "
                        + String.format("%,.2f", itcAtStake / 100.0) + ".");
            }

            // 3. master-data behaviour
            int behaviour = 0;
            if (rec != null) {
                List<VendorRecord> sameBank = rec.getBankAccount() == null ? List.of()
                        : byBank.getOrDefault(rec.getBankAccount(), List.of());
                if (sameBank.size() > 1) {
                    behaviour += 12;
                    notes.add("Bank account is shared with " + (sameBank.size() - 1) + " other vendor(s).");
                }
                if (rec.getCreatedDate() != null && !rec.getCreatedDate().isBefore(engagement.getFyStart())) {
                    behaviour += 8;
                    notes.add("Vendor was created during the financial year (" + rec.getCreatedDate() + ").");
                }
            } else if (!own.isEmpty()) {
                behaviour += 8;
                notes.add("Vendor appears in the purchase register but not in the vendor master.");
            }
            if (behaviour > 0) components.put("behaviour", Math.min(behaviour, CAP_BEHAVIOUR));

            // 4. spend concentration
            long spend = own.stream().mapToLong(PurchaseInvoice::getTaxablePaise).sum();
            double share = totalSpend == 0 ? 0 : spend * 100.0 / totalSpend;
            int concentration = share >= 20 ? 10 : share >= 10 ? 6 : share >= 5 ? 3 : 0;
            if (concentration > 0) {
                components.put("concentration", concentration);
                notes.add(String.format("%.1f%% of total purchase spend.", share));
            }

            // 5. amount patterns: round taxable values (multiples of Rs 1,000)
            if (own.size() >= 3) {
                long round = own.stream().filter(p -> p.getTaxablePaise() % 100_000_00L == 0
                        || p.getTaxablePaise() % 1_000_00L == 0).count();
                double ratio = round * 100.0 / own.size();
                int patterns = ratio >= 60 ? 10 : ratio >= 40 ? 5 : 0;
                if (patterns > 0) {
                    components.put("amountPatterns", patterns);
                    notes.add(String.format("%.0f%% of invoices are round thousand amounts.", ratio));
                }
            }

            int score = components.values().stream().mapToInt(Integer::intValue).sum();
            if (score == 0 && own.isEmpty()) continue; // nothing to report
            out.add(new VendorRisk(vid, name, gstin, own.size(), spend, round1(share),
                    itcAtStake, score, components, notes));
        }
        out.sort(Comparator.comparingInt(VendorRisk::score).reversed()
                .thenComparing(Comparator.comparingLong(VendorRisk::purchaseValuePaise).reversed()));
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
