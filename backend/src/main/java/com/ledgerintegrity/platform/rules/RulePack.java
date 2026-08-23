package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.rules.je.DuplicateJournalRule;
import com.ledgerintegrity.platform.rules.je.PostCloseBackdatedRule;
import com.ledgerintegrity.platform.rules.je.QuickReversalRule;
import com.ledgerintegrity.platform.rules.je.RoundAmountRule;
import com.ledgerintegrity.platform.rules.je.UnusualAccountPairRule;
import com.ledgerintegrity.platform.rules.je.VagueNarrationRule;
import com.ledgerintegrity.platform.rules.mot.PrivilegedDirectPostingRule;
import com.ledgerintegrity.platform.rules.mot.VendorMasterPaymentConflictRule;
import com.ledgerintegrity.platform.rules.pet.CloseVolumeSpikeRule;
import com.ledgerintegrity.platform.rules.pet.CloseWindowAccountRule;
import com.ledgerintegrity.platform.rules.pet.LatePostingRule;
import com.ledgerintegrity.platform.rules.sta.ActivitySpikeRule;
import com.ledgerintegrity.platform.rules.sta.ModifiedZScoreOutlierRule;
import com.ledgerintegrity.platform.rules.sta.RareUserAccountRule;
import com.ledgerintegrity.platform.rules.sta.ThresholdBunchingRule;
import com.ledgerintegrity.platform.rules.vp.BankChangeBeforePaymentRule;
import com.ledgerintegrity.platform.rules.vp.DuplicateInvoiceRule;
import com.ledgerintegrity.platform.rules.vp.DuplicateVendorRule;
import com.ledgerintegrity.platform.rules.vp.NewVendorActivityRule;
import com.ledgerintegrity.platform.rules.vp.ThresholdSplitRule;

import java.util.List;

/**
 * The versioned set of rules a run executes (JET-007 / AWP-003: the engagement retains
 * the exact rule versions used). Bump the version whenever rule logic changes.
 */
public record RulePack(String version, List<Rule> rules) {

    public static RulePack current() {
        return new RulePack("mvp-pack-0.5.0", List.of(
                // journal-entry rules
                new PostCloseBackdatedRule(),
                new QuickReversalRule(),
                new RoundAmountRule(),
                new VagueNarrationRule(),
                new DuplicateJournalRule(),
                new UnusualAccountPairRule(),
                // vendor / payment rules
                new DuplicateVendorRule(),
                new NewVendorActivityRule(),
                new DuplicateInvoiceRule(),
                new ThresholdSplitRule(),
                new BankChangeBeforePaymentRule(),
                // period-end rules (BRD §12)
                new CloseVolumeSpikeRule(),
                new LatePostingRule(),
                new CloseWindowAccountRule(),
                // management-override rules (BRD §13)
                new PrivilegedDirectPostingRule(),
                new VendorMasterPaymentConflictRule(),
                // statistical layer v2 (integrity-core guide §3-4)
                new ModifiedZScoreOutlierRule(),
                new RareUserAccountRule(),
                new ThresholdBunchingRule(),
                new ActivitySpikeRule()));
    }
}
