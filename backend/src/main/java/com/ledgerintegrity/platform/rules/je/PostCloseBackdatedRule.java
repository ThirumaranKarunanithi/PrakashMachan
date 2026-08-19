package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.List;

/**
 * JE-03/JE-04: entries created after the financial close but posted into the closed
 * period. Elevated severity when a privileged user posted them (JE-02 signal).
 */
public class PostCloseBackdatedRule implements Rule {

    @Override public String id() { return "JE-03"; }
    @Override public String name() { return "Post-close / backdated entry"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.createdAt() == null || v.txnDate() == null) continue;
            boolean postedIntoClosedPeriod = !v.txnDate().isAfter(ctx.closeDate());
            boolean createdAfterClose = v.createdAt().toLocalDate().isAfter(ctx.closeDate());
            if (postedIntoClosedPeriod && createdAfterClose) {
                boolean privileged = v.userId() != null && ctx.params().privilegedUsers().contains(v.userId());
                findings.add(new Finding(id(), name(),
                        privileged ? Finding.Severity.HIGH : Finding.Severity.MEDIUM,
                        v.amountPaise(),
                        "Voucher " + v.id() + " posted to " + v.txnDate()
                                + " but created " + v.createdAt() + " (after close " + ctx.closeDate() + ") by "
                                + v.userId() + (privileged ? " [privileged user]" : "") + ".",
                        List.of(v.id()),
                        v.sourceRefs()));
            }
        }
        return findings;
    }
}
