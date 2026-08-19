package com.ledgerintegrity.platform.rules.vp;

import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** VP-03: a vendor created during the financial year with immediate significant activity. */
public class NewVendorActivityRule implements Rule {

    @Override public String id() { return "VP-03"; }
    @Override public String name() { return "New vendor with immediate activity"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        List<Finding> findings = new ArrayList<>();
        for (VendorRecord v : ctx.vendors()) {
            if (v.getCreatedDate() == null || v.getCreatedDate().isBefore(ctx.fyStart())) continue;
            List<PurchaseInvoice> invoices = ctx.purchases().stream()
                    .filter(p -> v.getVendorId().equals(p.getVendorId()))
                    .toList();
            long total = invoices.stream().mapToLong(PurchaseInvoice::getTotalPaise).sum();
            if (total < ctx.params().newVendorActivityThresholdPaise()) continue;

            // include the purchase vouchers so this consolidates with other signals on the same buys
            Set<String> tokens = new LinkedHashSet<>();
            tokens.add("VENDOR:" + v.getVendorId());
            invoices.forEach(p -> {
                if (p.getVoucherId() != null && !p.getVoucherId().isBlank()) tokens.add(p.getVoucherId());
            });
            findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                    total,
                    "Vendor " + v.getVendorId() + " \"" + v.getName() + "\" was created " + v.getCreatedDate()
                            + " by " + v.getCreatedBy() + "; Rs " + rupees(total)
                            + " of invoices were booked soon after creation ("
                            + invoices.stream().map(PurchaseInvoice::getInvoiceNo).collect(Collectors.joining(", ")) + ").",
                    List.copyOf(tokens),
                    v.getSourceFile() + ":" + v.getSourceRow() + " "
                            + invoices.stream().map(p -> p.getSourceFile() + ":" + p.getSourceRow())
                            .collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
