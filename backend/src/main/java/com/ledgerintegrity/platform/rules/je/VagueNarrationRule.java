package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** JE-10: manual journals without enough explanation to understand the business purpose. */
public class VagueNarrationRule implements Rule {

    @Override public String id() { return "JE-10"; }
    @Override public String name() { return "Vague or blank narration"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (!v.isManualJournal()) continue;
            String narration = v.narration() == null ? "" : v.narration().trim();
            boolean vague = narration.isEmpty()
                    || ctx.params().vagueWords().contains(narration.toLowerCase(Locale.ROOT));
            if (vague) {
                findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                        v.amountPaise(),
                        "Manual journal " + v.id() + " by " + v.userId() + " on " + v.txnDate()
                                + (narration.isEmpty() ? " has a blank narration."
                                : " has the vague narration \"" + narration + "\"."),
                        List.of(v.id()),
                        v.sourceRefs()));
            }
        }
        return findings;
    }
}
