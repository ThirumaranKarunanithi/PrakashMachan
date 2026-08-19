package com.ledgerintegrity.platform.rules.pet;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PET-03/PET-04: sensitive account activity inside the close window — provisions and
 * accruals raised at the last moment, and suspense/clearing accounts used during close.
 * Estimates and provisions can be legitimate; the flag asks for the supporting calculation.
 */
public class CloseWindowAccountRule implements Rule {

    @Override public String id() { return "PET-04"; }
    @Override public String name() { return "Provision / accrual / suspense activity at close"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        LocalDate windowStart = ctx.closeDate().minusDays(ctx.params().closeWindowDays());
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.txnDate() == null || v.txnDate().isBefore(windowStart) || v.txnDate().isAfter(ctx.closeDate())) continue;
            String sensitive = null;
            for (LedgerRow line : v.lines()) {
                String acct = line.accountName().toLowerCase(Locale.ROOT);
                if (acct.contains("provision") || acct.contains("accrual")) { sensitive = line.accountName(); break; }
                if (acct.contains("suspense") || acct.contains("clearing")) { sensitive = line.accountName(); break; }
            }
            if (sensitive == null) continue;
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    v.amountPaise(),
                    "Voucher " + v.id() + " dated " + v.txnDate() + " (close window) posts Rs "
                            + rupees(v.amountPaise()) + " to \"" + sensitive + "\" by " + v.userId()
                            + ". Request the supporting calculation and approval; check for post-close reversal.",
                    List.of(v.id()),
                    v.sourceRefs()));
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
