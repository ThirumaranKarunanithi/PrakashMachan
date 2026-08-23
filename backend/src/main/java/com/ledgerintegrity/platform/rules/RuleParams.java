package com.ledgerintegrity.platform.rules;

import java.util.Set;

/**
 * Engagement-configurable rule parameters (JET-007). The exact values used by a run
 * are snapshotted onto the RuleRun record so results are reproducible.
 */
public record RuleParams(
        /** Users whose postings deserve elevated severity (admins, finance heads). */
        Set<String> privilegedUsers,
        /** Manual journals at or above this amount are candidates for the round-amount rule (paise). */
        long roundAmountThresholdPaise,
        /** ...when the amount is an exact multiple of this (paise). */
        long roundAmountMultiplePaise,
        /** Narrations equal to any of these (case-insensitive) count as vague. */
        Set<String> vagueWords,
        /** Payment approval threshold used by the split-payment rule VP-05 (paise). */
        long approvalThresholdPaise,
        /** Days within which sub-threshold payments to one payee are grouped (VP-05). */
        int splitWindowDays,
        /** New-vendor activity above this amount is flagged (VP-03, paise). */
        long newVendorActivityThresholdPaise,
        /** Days before the close date that count as the close window (PET, BRD §12). */
        int closeWindowDays,
        /** Creation more than this many days after the transaction date counts as late posting (PET-02). */
        int latePostingLagDays,
        /** Close-window daily volume above this multiple of the yearly baseline is flagged (PET-01). */
        double closeVolumeMultiple,
        /** |Modified Z-score| at or above this flags a peer-group amount outlier (STA-01). */
        double modifiedZThreshold,
        /** A user-account combination seen this many times or fewer is rare (STA-02). */
        int rarityMinSupport,
        /** Trailing days forming the rolling activity baseline (STA-04). */
        int spikeBaselineDays,
        /** Daily count above baseline median + this many MADs is a spike (STA-04). */
        double spikeMadMultiplier
) {
    public static RuleParams defaults() {
        return new RuleParams(
                Set.of(),
                100_000_00L,   // Rs 1,00,000
                10_000_00L,    // multiples of Rs 10,000
                Set.of("adjustment", "adj", "misc", "entry", "correction", "sundry"),
                50_000_00L,    // Rs 50,000 approval threshold
                7,
                100_000_00L,   // Rs 1,00,000 new-vendor activity
                7,             // close window: last 7 days of the year
                5,             // late posting: created > 5 days after the transaction date
                3.0,           // close-window volume: > 3x the yearly daily baseline
                2.0,           // Modified Z-score threshold: amounts are log-transformed (heavy-tailed
                               // rupee data), which compresses deviations, so the classic raw-scale 3.5
                               // would almost never fire; 2.0 on the log scale flags roughly 4x-and-above
                               // the robust peer range. Firm-configurable like every parameter.
                2,             // user-account combos seen <= 2 times are rare
                30,            // 30-day rolling activity baseline
                4.0);          // spike: > median + 4 MADs
    }

    public RuleParams withPrivilegedUsers(Set<String> users) {
        return new RuleParams(users, roundAmountThresholdPaise, roundAmountMultiplePaise, vagueWords,
                approvalThresholdPaise, splitWindowDays, newVendorActivityThresholdPaise,
                closeWindowDays, latePostingLagDays, closeVolumeMultiple,
                modifiedZThreshold, rarityMinSupport, spikeBaselineDays, spikeMadMultiplier);
    }
}
