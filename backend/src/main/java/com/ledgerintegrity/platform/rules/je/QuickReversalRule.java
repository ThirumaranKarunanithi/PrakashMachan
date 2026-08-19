package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JE-06/JE-09: a voucher that reverses an earlier voucher, where identifiers link them.
 * Reversals that cross the reporting date deserve particular attention (SA 240 signal).
 */
public class QuickReversalRule implements Rule {

    @Override public String id() { return "JE-09"; }
    @Override public String name() { return "Reversal of an earlier entry"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        Map<String, Voucher> byId = new HashMap<>();
        for (Voucher v : ctx.vouchers()) byId.put(v.id(), v);

        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.reversalOf() == null) continue;
            Voucher orig = byId.get(v.reversalOf());
            if (orig == null) continue;
            long days = ChronoUnit.DAYS.between(orig.txnDate(), v.txnDate());
            boolean crossesClose = !orig.txnDate().isAfter(ctx.closeDate()) && v.txnDate().isAfter(ctx.closeDate());
            findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                    v.amountPaise(),
                    "Voucher " + v.id() + " reverses " + orig.id() + " after " + days
                            + " day(s); original posted " + orig.txnDate() + " by " + orig.userId()
                            + ", reversal by " + v.userId() + "."
                            + (crossesClose ? " The reversal crosses the reporting date " + ctx.closeDate() + "." : ""),
                    List.of(orig.id(), v.id()),
                    orig.sourceRefs() + " " + v.sourceRefs()));
        }
        return findings;
    }
}
