package com.ledgerintegrity.platform.rules.pet;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * PET-01/PET-03: unusual end-period volume — the close window's daily voucher rate
 * compared with the year's baseline (BRD §12: period-end activity is expected to be
 * higher; unusual does not mean wrong, so the multiple is configurable).
 */
public class CloseVolumeSpikeRule implements Rule {

    @Override public String id() { return "PET-01"; }
    @Override public String name() { return "Close-window volume spike"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        LocalDate windowStart = ctx.closeDate().minusDays(ctx.params().closeWindowDays());
        long yearDays = Math.max(1, ChronoUnit.DAYS.between(ctx.fyStart(), ctx.closeDate()) + 1);
        long windowDays = ctx.params().closeWindowDays() + 1;

        long total = 0, inWindow = 0;
        for (Voucher v : ctx.vouchers()) {
            if (v.txnDate() == null || v.txnDate().isBefore(ctx.fyStart()) || v.txnDate().isAfter(ctx.closeDate())) continue;
            total++;
            if (!v.txnDate().isBefore(windowStart)) inWindow++;
        }
        if (total < 100) return List.of(); // too small for a meaningful baseline

        double baselinePerDay = (double) (total - inWindow) / (yearDays - windowDays);
        double windowPerDay = (double) inWindow / windowDays;
        if (baselinePerDay <= 0 || windowPerDay <= baselinePerDay * ctx.params().closeVolumeMultiple()) {
            return List.of();
        }
        // largest close-window vouchers carry the case so it consolidates with related signals
        List<String> top = ctx.vouchers().stream()
                .filter(v -> v.txnDate() != null && !v.txnDate().isBefore(windowStart) && !v.txnDate().isAfter(ctx.closeDate()))
                .sorted(Comparator.comparingLong(Voucher::amountPaise).reversed())
                .limit(10)
                .map(Voucher::id)
                .sorted()
                .toList();
        return List.of(new Finding(id(), name(), Finding.Severity.MEDIUM, 0,
                String.format("The last %d days of the year averaged %.1f vouchers/day against a baseline of %.1f/day"
                                + " (%.1fx). Elevated closing activity is normal; review the largest entries for cut-off"
                                + " and support.", windowDays, windowPerDay, baselinePerDay, windowPerDay / baselinePerDay),
                top, "close-window population"));
    }
}
