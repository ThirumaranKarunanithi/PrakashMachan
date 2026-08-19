package com.ledgerintegrity.platform.rules.mot;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.List;

/**
 * MOT-01: senior or administrative users posting manual journals directly.
 * An authorised override can still require audit attention (BRD §13) — the question
 * is whether it was justified, documented and independently reviewed.
 */
public class PrivilegedDirectPostingRule implements Rule {

    @Override public String id() { return "MOT-01"; }
    @Override public String name() { return "Privileged user posting manual journals directly"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (!v.isManualJournal()) continue;
            if (v.userId() == null || !ctx.params().privilegedUsers().contains(v.userId())) continue;
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    v.amountPaise(),
                    "Manual journal " + v.id() + " of Rs " + rupees(v.amountPaise())
                            + " was posted directly by privileged user " + v.userId() + " on " + v.txnDate()
                            + ". Confirm whether an independent review or approval exists.",
                    List.of(v.id()),
                    v.sourceRefs()));
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
