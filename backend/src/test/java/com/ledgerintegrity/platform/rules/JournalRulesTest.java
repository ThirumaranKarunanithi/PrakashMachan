package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.Lineage;
import com.ledgerintegrity.platform.rules.je.PostCloseBackdatedRule;
import com.ledgerintegrity.platform.rules.je.QuickReversalRule;
import com.ledgerintegrity.platform.rules.je.RoundAmountRule;
import com.ledgerintegrity.platform.rules.je.VagueNarrationRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JournalRulesTest {

    private static final LocalDate CLOSE = LocalDate.of(2025, 3, 31);

    private static LedgerRow row(String voucher, String type, LocalDate txn, LocalDateTime created,
                                 long debitPaise, long creditPaise, String narration, String source,
                                 String user, String reversalOf) {
        return new LedgerRow(voucher, type, txn, created, "5901", "Misc",
                debitPaise == 0 ? null : debitPaise, creditPaise == 0 ? null : creditPaise,
                narration, source, user, reversalOf, new Lineage("gl.csv", 2));
    }

    private static Rule.Context ctx(RuleParams params, LedgerRow... rows) {
        return new Rule.Context(CLOSE, LocalDate.of(2024, 4, 1), params, Voucher.group(List.of(rows)),
                List.of(), List.of(), List.of());
    }

    @Test
    void postCloseBackdatedFlagsEntriesCreatedAfterCloseAndElevatesPrivilegedUsers() {
        RuleParams params = RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1"));
        var findings = new PostCloseBackdatedRule().evaluate(ctx(params,
                // posted into FY, created after close, privileged -> HIGH
                row("V1", "Journal", LocalDate.of(2025, 3, 31), LocalDateTime.of(2025, 4, 4, 22, 15),
                        1_000_00, 0, "provision", "Manual", "ADMIN-1", null),
                // same shape, normal user -> MEDIUM
                row("V2", "Journal", LocalDate.of(2025, 3, 30), LocalDateTime.of(2025, 4, 2, 10, 0),
                        2_000_00, 0, "reclass", "Manual", "ACCT-1", null),
                // created before close -> not flagged
                row("V3", "Journal", LocalDate.of(2025, 3, 20), LocalDateTime.of(2025, 3, 20, 12, 0),
                        3_000_00, 0, "normal", "Manual", "ACCT-1", null),
                // posted after close -> not backdated
                row("V4", "Journal", LocalDate.of(2025, 4, 5), LocalDateTime.of(2025, 4, 6, 12, 0),
                        4_000_00, 0, "next year", "Manual", "ACCT-1", null)));

        assertEquals(2, findings.size());
        assertEquals(Finding.Severity.HIGH, findings.get(0).severity());
        assertTrue(findings.get(0).reason().contains("privileged user"));
        assertEquals(Finding.Severity.MEDIUM, findings.get(1).severity());
    }

    @Test
    void quickReversalLinksOriginalAndReversalAndNotesCloseCrossing() {
        var findings = new QuickReversalRule().evaluate(ctx(RuleParams.defaults(),
                row("V1", "Journal", LocalDate.of(2025, 3, 31), null, 49_00_000_00L, 0, "provision", "Manual", "ADMIN-1", null),
                row("V2", "Journal", LocalDate.of(2025, 4, 11), null, 49_00_000_00L, 0, "reversal", "Manual", "ADMIN-1", "V1")));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals(Finding.Severity.HIGH, f.severity());
        assertEquals(List.of("V1", "V2"), f.voucherIds());
        assertTrue(f.reason().contains("11 day(s)"));
        assertTrue(f.reason().contains("crosses the reporting date"));
    }

    @Test
    void roundAmountFlagsOnlyLargeExactMultiplesOfManualJournals() {
        var findings = new RoundAmountRule().evaluate(ctx(RuleParams.defaults(),
                row("V1", "Journal", LocalDate.of(2024, 11, 14), null, 2_00_000_00L, 0, "adjustment", "Manual", "ACCT-3", null),
                row("V2", "Journal", LocalDate.of(2024, 12, 1), null, 2_00_123_45L, 0, "odd amount", "Manual", "ACCT-3", null),
                row("V3", "Journal", LocalDate.of(2024, 12, 2), null, 50_000_00L, 0, "below threshold", "Manual", "ACCT-3", null),
                row("V4", "Purchase", LocalDate.of(2024, 12, 3), null, 3_00_000_00L, 0, "system purchase", "PurchaseModule", "ACCT-1", null)));

        assertEquals(1, findings.size());
        assertEquals(List.of("V1"), findings.get(0).voucherIds());
    }

    @Test
    void vagueNarrationFlagsConfiguredWordsAndBlank() {
        var findings = new VagueNarrationRule().evaluate(ctx(RuleParams.defaults(),
                row("V1", "Journal", LocalDate.of(2024, 11, 14), null, 1_00_000_00L, 0, "adjustment", "Manual", "ACCT-3", null),
                row("V2", "Journal", LocalDate.of(2024, 11, 15), null, 1_00_000_00L, 0, "  ", "Manual", "ACCT-3", null),
                row("V3", "Journal", LocalDate.of(2024, 11, 16), null, 1_00_000_00L, 0, "Being rent for November paid to landlord", "Manual", "ACCT-3", null)));

        assertEquals(2, findings.size());
        assertTrue(findings.get(0).reason().contains("vague narration"));
        assertTrue(findings.get(1).reason().contains("blank narration"));
    }
}
