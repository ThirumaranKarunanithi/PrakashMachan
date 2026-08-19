package com.ledgerintegrity.platform.bank;

import com.ledgerintegrity.platform.bank.persist.BankMatchResult.MatchType;
import com.ledgerintegrity.platform.bank.persist.BankMatchResultRepository;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BK-01..05 against the Phase 0 CLIENT-A bank files. Expected outcome from the seeded
 * anomalies A9-A11: 4 bank-only charges, 1 stale-cheque books-only item, and the
 * 3x1,00,000 grouped receipt (AGG300K) matched one-to-many.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:banktestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class BankReconciliationIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("bank_statement.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired BankImportService importService;
    @Autowired BankReconciliationService reconciliation;
    @Autowired BankMatchResultRepository matches;
    @Autowired ExceptionCaseRepository exceptions;

    @Test
    void matchesGroupsAndClassifiesSeededBankAnomalies() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        var s = importService.importStatement(e.getId(), "bank_statement.csv",
                Files.readAllBytes(SAMPLE.resolve("bank_statement.csv")));
        var l = importService.importLedger(e.getId(), "bank_ledger.csv",
                Files.readAllBytes(SAMPLE.resolve("bank_ledger.csv")));
        assertEquals(471, s.added());
        assertEquals(470, l.added());

        var r = reconciliation.reconcile(e.getId());
        assertEquals(141, r.counts().get(MatchType.EXACT));
        assertEquals(325, r.counts().get(MatchType.TOLERANCE));
        assertEquals(1, r.counts().get(MatchType.GROUPED));   // A11: AGG300K = 3 receipts
        assertEquals(4, r.counts().get(MatchType.BANK_ONLY)); // A9: quarterly charges
        assertEquals(1, r.counts().get(MatchType.BOOKS_ONLY)); // A10: cheque 004512

        // the grouped match names all three receipt vouchers
        var grouped = matches.findByEngagementIdAndMatchTypeOrderByAmountPaiseDesc(e.getId(), MatchType.GROUPED);
        assertEquals(1, grouped.size());
        assertEquals("AGG300K", grouped.get(0).getReference());
        assertEquals(3, grouped.get(0).getVoucherIds().split(" ").length);
        assertEquals(300_000_00L, grouped.get(0).getAmountPaise());

        // BKR-006: after recognised reconciling items, nothing is unexplained
        assertEquals(0L, r.unexplainedPaise());

        // 4 bank-only + 1 books-only exceptions raised through the shared path
        assertEquals(5, r.exceptionsCreated());
        var all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId());
        assertEquals(4, all.stream().filter(x -> x.getRuleId().equals("BK-04B")).count());
        assertEquals(1, all.stream().filter(x -> x.getRuleId().equals("BK-04L")).count());
        assertTrue(all.stream().filter(x -> x.getRuleId().equals("BK-04L"))
                .allMatch(x -> x.getReason().contains("CHQ004512")));

        // idempotent re-run: same classification, no new exceptions
        var r2 = reconciliation.reconcile(e.getId());
        assertEquals(r.counts(), r2.counts());
        assertEquals(0, r2.exceptionsCreated());
        assertEquals(5, r2.skippedExisting());
    }
}
