package com.ledgerintegrity.platform.rules.mot;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MOT-02: create-vendor-and-pay / change-master-and-pay conflict — the same person
 * touches a vendor's sensitive master data (creation, bank account, GSTIN, status)
 * and then participates in a payment to that vendor shortly afterwards. Normal
 * segregation of duties keeps these steps with different people (BRD §13 example).
 */
public class VendorMasterPaymentConflictRule implements Rule {

    private static final int WINDOW_DAYS = 30;
    private static final Set<String> SENSITIVE_FIELDS = Set.of("bank_account", "ifsc", "gstin", "status");

    private record MasterEvent(LocalDate date, String user, String what) {}

    @Override public String id() { return "MOT-02"; }
    @Override public String name() { return "Same user maintains vendor master and pays the vendor"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        List<Finding> findings = new ArrayList<>();
        for (VendorRecord vendor : ctx.vendors()) {
            List<MasterEvent> events = new ArrayList<>();
            if (vendor.getCreatedDate() != null && vendor.getCreatedBy() != null && !vendor.getCreatedBy().isBlank()) {
                events.add(new MasterEvent(vendor.getCreatedDate(), vendor.getCreatedBy(), "created the vendor"));
            }
            for (AuditTrailEvent e : ctx.auditEvents()) {
                if (!"VendorMaster".equalsIgnoreCase(e.getObjectType())) continue;
                if (!vendor.getVendorId().equals(e.getRecordId())) continue;
                if (!SENSITIVE_FIELDS.contains(e.getField().toLowerCase(Locale.ROOT))) continue;
                events.add(new MasterEvent(e.getTimestamp().toLocalDate(), e.getUserId(),
                        "changed " + e.getField() + " at " + e.getTimestamp()));
            }
            if (events.isEmpty()) continue;

            List<String> sequence = new ArrayList<>();
            Set<String> tokens = new LinkedHashSet<>();
            long exposure = 0;
            for (Voucher v : ctx.vouchers()) {
                if (!"Payment".equalsIgnoreCase(v.type())) continue;
                if (!v.narration().contains(vendor.getName())) continue;
                for (MasterEvent e : events) {
                    if (v.userId() == null || !v.userId().equalsIgnoreCase(e.user())) continue;
                    long gap = ChronoUnit.DAYS.between(e.date(), v.txnDate());
                    if (gap < 0 || gap > WINDOW_DAYS) continue;
                    sequence.add(e.user() + " " + e.what() + " on " + e.date() + " and posted payment "
                            + v.id() + " of Rs " + rupees(v.amountPaise()) + " " + gap + " day(s) later");
                    tokens.add(v.id());
                    exposure += v.amountPaise();
                    break;
                }
            }
            if (sequence.isEmpty()) continue;
            tokens.add("VENDOR:" + vendor.getVendorId());
            findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                    exposure,
                    "Segregation-of-duties conflict for " + vendor.getVendorId() + " \"" + vendor.getName() + "\": "
                            + String.join("; ", sequence)
                            + ". An override can be legitimate — confirm justification and independent review.",
                    tokens.stream().sorted().toList(),
                    vendor.getSourceFile() + ":" + vendor.getSourceRow()));
        }
        return findings.stream()
                .sorted((a, b) -> Long.compare(b.exposurePaise(), a.exposurePaise()))
                .collect(Collectors.toList());
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
