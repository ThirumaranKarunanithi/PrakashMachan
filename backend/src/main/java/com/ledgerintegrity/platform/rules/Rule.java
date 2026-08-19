package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;

import java.time.LocalDate;
import java.util.List;

/** One deterministic audit test. Rules never conclude fraud — they explain why review is warranted. */
public interface Rule {

    /**
     * Everything a rule may reason over. Lists are empty (never null) when the
     * engagement has not imported that source — rules must degrade gracefully.
     */
    record Context(LocalDate closeDate,
                   LocalDate fyStart,
                   RuleParams params,
                   List<Voucher> vouchers,
                   List<VendorRecord> vendors,
                   List<PurchaseInvoice> purchases,
                   List<AuditTrailEvent> auditEvents) {}

    String id();

    String name();

    List<Finding> evaluate(Context ctx);
}
