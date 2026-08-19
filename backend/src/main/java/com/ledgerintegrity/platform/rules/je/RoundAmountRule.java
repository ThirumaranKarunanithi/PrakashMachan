package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.List;

/**
 * JE-07: manually constructed values — large manual journals in exact round amounts.
 * A round amount is not wrong by itself; it is a review signal (BRD §6 boundary).
 */
public class RoundAmountRule implements Rule {

    @Override public String id() { return "JE-07"; }
    @Override public String name() { return "Round-amount manual journal"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        long threshold = ctx.params().roundAmountThresholdPaise();
        long multiple = ctx.params().roundAmountMultiplePaise();
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (!v.isManualJournal()) continue;
            long amount = v.amountPaise();
            if (amount >= threshold && multiple > 0 && amount % multiple == 0) {
                findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                        amount,
                        "Manual journal " + v.id() + " by " + v.userId() + " on " + v.txnDate()
                                + " is an exact round amount of Rs " + rupees(amount) + ".",
                        List.of(v.id()),
                        v.sourceRefs()));
            }
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
