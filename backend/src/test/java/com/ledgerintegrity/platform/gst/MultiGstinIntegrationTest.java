package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GST-009: an entity with multiple GSTINs — the purchase register carries an optional
 * own_gstin column and the registration summary reports each registration separately.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:multigstintestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MultiGstinIntegrationTest {

    private static final String REG_TN = "33AAACX0000A1Z5"; // Tamil Nadu registration
    private static final String REG_KA = "29AAACX0000A1Z2"; // Karnataka registration

    @Autowired EngagementRepository engagements;
    @Autowired GstImportService importService;
    @Autowired GstReconciliationService reconciliation;

    @Test
    void registrationSummarySplitsResultsPerOwnGstin() {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "MULTI-GSTIN",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        // 4 purchase invoices across two of the entity's own registrations
        String purchases = """
                invoice_no,invoice_date,vendor_id,vendor_name,gstin,taxable_value,tax_amount,total,voucher_id,own_gstin
                INV-100,2024-06-01,V1,Alpha Traders,33AAAAA1111A1Z1,100000.00,18000.00,118000.00,PUR-1,%s
                INV-101,2024-06-10,V2,Beta Supplies,33BBBBB2222B1Z2,50000.00,9000.00,59000.00,PUR-2,%s
                INV-200,2024-07-05,V3,Gamma Mills,29CCCCC3333C1Z3,80000.00,14400.00,94400.00,PUR-3,%s
                INV-201,2024-07-20,V4,Delta Metals,29DDDDD4444D1Z4,20000.00,3600.00,23600.00,PUR-4,%s
                """.formatted(REG_TN, REG_TN, REG_KA, REG_KA);

        // 2B: INV-100 matches, INV-101 taxable differs by Rs 5,000, INV-200 matches,
        // INV-201 missing (books-only), plus one portal-only invoice with no own_gstin
        String gstr2b = """
                supplier_gstin,supplier_name,invoice_no,invoice_date,taxable_value,tax_amount,filing_status
                33AAAAA1111A1Z1,Alpha Traders,INV-100,2024-06-01,100000.00,18000.00,FILED
                33BBBBB2222B1Z2,Beta Supplies,INV-101,2024-06-10,45000.00,8100.00,FILED
                29CCCCC3333C1Z3,Gamma Mills,INV-200,2024-07-05,80000.00,14400.00,FILED
                33EEEEE5555E1Z5,Extra Vendor,INV-999,2024-08-01,10000.00,1800.00,FILED
                """;

        var p = importService.importPurchaseRegister(e.getId(), "purchase_register.csv",
                purchases.getBytes(StandardCharsets.UTF_8));
        assertEquals(4, p.added());
        importService.importGstr2b(e.getId(), "gstr2b.csv", gstr2b.getBytes(StandardCharsets.UTF_8));

        var r = reconciliation.reconcile(e.getId());
        assertEquals(2, r.counts().getOrDefault(Category.MATCHED, 0));
        assertEquals(1, r.counts().getOrDefault(Category.VALUE_MISMATCH, 0));
        assertEquals(1, r.counts().getOrDefault(Category.BOOKS_ONLY, 0));
        assertEquals(1, r.counts().getOrDefault(Category.G2B_ONLY, 0));

        List<Map<String, Object>> summary = reconciliation.registrationSummary(e.getId());
        assertEquals(3, summary.size()); // TN, KA, and the ownGstin-less portal-only bucket

        Map<String, Object> ka = rowFor(summary, REG_KA);
        assertEquals(1L, ka.get("PURCHASE:MATCHED"));
        assertEquals(1L, ka.get("PURCHASE:BOOKS_ONLY"));
        assertNull(ka.get("PURCHASE:VALUE_MISMATCH"));

        Map<String, Object> tn = rowFor(summary, REG_TN);
        assertEquals(1L, tn.get("PURCHASE:MATCHED"));
        assertEquals(1L, tn.get("PURCHASE:VALUE_MISMATCH"));
        // Rs 900 tax difference on INV-101 shows up only under the TN registration
        assertEquals(90_000L, tn.get("taxAtStakePaise"));

        Map<String, Object> other = rowFor(summary, "(single registration)");
        assertEquals(1L, other.get("PURCHASE:G2B_ONLY"));
    }

    private static Map<String, Object> rowFor(List<Map<String, Object>> summary, String reg) {
        return summary.stream().filter(m -> reg.equals(m.get("ownGstin"))).findFirst().orElseThrow();
    }
}
