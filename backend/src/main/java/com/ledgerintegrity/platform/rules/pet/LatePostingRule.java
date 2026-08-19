package com.ledgerintegrity.platform.rules.pet;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * PET-02: close-window transactions whose creation lags the transaction date —
 * entries dated inside the closing period but actually recorded noticeably later.
 * Complements JE-03 (which needs creation strictly after the close date).
 */
public class LatePostingRule implements Rule {

    @Override public String id() { return "PET-02"; }
    @Override public String name() { return "Late posting into the close window"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        LocalDate windowStart = ctx.closeDate().minusDays(ctx.params().closeWindowDays());
        List<Finding> findings = new ArrayList<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.createdAt() == null || v.txnDate() == null) continue;
            if (v.txnDate().isBefore(windowStart) || v.txnDate().isAfter(ctx.closeDate())) continue;
            long lag = ChronoUnit.DAYS.between(v.txnDate(), v.createdAt().toLocalDate());
            if (lag <= ctx.params().latePostingLagDays()) continue;
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    v.amountPaise(),
                    "Voucher " + v.id() + " is dated " + v.txnDate() + " (inside the last "
                            + ctx.params().closeWindowDays() + " days of the year) but was created "
                            + v.createdAt() + " — " + lag + " days later, by " + v.userId() + ".",
                    List.of(v.id()),
                    v.sourceRefs()));
        }
        return findings;
    }
}
