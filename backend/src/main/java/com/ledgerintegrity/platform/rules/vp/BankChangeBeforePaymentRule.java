package com.ledgerintegrity.platform.rules.vp;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VP-06 / ATR-004: a vendor's bank account changed shortly before a payment was
 * released — the sequence a payment-diversion attempt follows (BRD §10 example).
 */
public class BankChangeBeforePaymentRule implements Rule {

    private static final int WINDOW_DAYS = 3;

    @Override public String id() { return "VP-06"; }
    @Override public String name() { return "Bank detail changed shortly before payment"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        Map<String, VendorRecord> vendorById = new HashMap<>();
        for (VendorRecord v : ctx.vendors()) vendorById.put(v.getVendorId(), v);

        List<Finding> findings = new ArrayList<>();
        for (AuditTrailEvent event : ctx.auditEvents()) {
            if (!"bank_account".equalsIgnoreCase(event.getField())) continue;
            VendorRecord vendor = vendorById.get(event.getRecordId());
            if (vendor == null) continue;
            LocalDate changeDate = event.getTimestamp().toLocalDate();
            boolean afterHours = event.getTimestamp().getHour() >= 20 || event.getTimestamp().getHour() < 7;

            for (Voucher v : ctx.vouchers()) {
                if (!"Payment".equalsIgnoreCase(v.type())) continue;
                if (!v.narration().contains(vendor.getName())) continue;
                long gap = ChronoUnit.DAYS.between(changeDate, v.txnDate());
                if (gap < 0 || gap > WINDOW_DAYS) continue;
                findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                        v.amountPaise(),
                        "Bank account of " + vendor.getVendorId() + " \"" + vendor.getName() + "\" was changed "
                                + event.getTimestamp() + " by " + event.getUserId()
                                + (afterHours ? " (after hours)" : "") + "; payment " + v.id() + " of Rs "
                                + rupees(v.amountPaise()) + " was released " + v.txnDate() + ", "
                                + gap + " day(s) later.",
                        List.of(v.id(), "VENDOR:" + vendor.getVendorId()),
                        event.getSourceFile() + ":" + event.getSourceRow() + " " + v.sourceRefs()));
            }
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
