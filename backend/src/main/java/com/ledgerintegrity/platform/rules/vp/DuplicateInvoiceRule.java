package com.ledgerintegrity.platform.rules.vp;

import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VP-04: near-duplicate purchase invoices — same supplier, identical value, and the
 * same numeric invoice core (catches TT/2287 vs TT/2287A style double bookings).
 */
public class DuplicateInvoiceRule implements Rule {

    @Override public String id() { return "VP-04"; }
    @Override public String name() { return "Duplicate / near-duplicate invoice"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        Map<String, List<PurchaseInvoice>> byKey = new LinkedHashMap<>();
        for (PurchaseInvoice p : ctx.purchases()) {
            String numericCore = p.getInvoiceNo().replaceAll("[^0-9]", "");
            if (numericCore.isEmpty()) continue;
            String key = p.getGstin() + "|" + p.getTotalPaise() + "|" + numericCore;
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<Finding> findings = new ArrayList<>();
        for (List<PurchaseInvoice> group : byKey.values()) {
            if (group.size() < 2) continue;
            // identical invoice numbers on both rows are a data-quality duplicate, not this rule
            if (group.stream().map(PurchaseInvoice::getInvoiceNo).distinct().count() < 2) continue;
            PurchaseInvoice first = group.get(0);
            findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                    first.getTotalPaise(),
                    "Invoices " + group.stream()
                            .map(p -> p.getInvoiceNo() + " (" + p.getInvoiceDate() + ")")
                            .collect(Collectors.joining(" and "))
                            + " from " + first.getVendorName() + " have the identical value Rs "
                            + rupees(first.getTotalPaise())
                            + " and matching numeric core — possible double booking.",
                    group.stream().map(DuplicateInvoiceRule::voucherToken).sorted().toList(),
                    group.stream().map(p -> p.getSourceFile() + ":" + p.getSourceRow())
                            .collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static String voucherToken(PurchaseInvoice p) {
        return (p.getVoucherId() != null && !p.getVoucherId().isBlank())
                ? p.getVoucherId()
                : "INV:" + p.getGstin() + "/" + p.getInvoiceNo();
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
