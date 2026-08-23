package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
import com.ledgerintegrity.platform.workpaper.WorkpaperService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Screen-7/8: source-row context, Excel register, workpaper PDF, Audit File Pack. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:exporttestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class ExportIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired RuleEngineService engine;
    @Autowired WorkpaperService workpapers;
    @Autowired MappingProfileRepository profiles;
    @Autowired LedgerEntryRepository entries;
    @Autowired ExportController exports;
    @Autowired com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository wpRepo;

    @Autowired com.ledgerintegrity.platform.auth.persist.AppUserRepository users;

    @Test
    void sourceContextExcelPdfAndAuditPackAllWork() throws IOException {
        UUID firmId = UUID.randomUUID();
        // the export endpoints run behind TenantGuard: authenticate as a firm user
        users.save(new com.ledgerintegrity.platform.auth.persist.AppUser(UUID.randomUUID(), firmId,
                "partner@export.test", "x", "Export Partner",
                com.ledgerintegrity.platform.auth.persist.AppUser.Role.PARTNER, Instant.now()));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "partner@export.test", null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PARTNER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        Engagement e = new Engagement(UUID.randomUUID(), firmId, "EXPORT-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        workpapers.generate(e.getId());

        // ---- source-row context: rows around the 49L voucher, flagged rows marked ----
        var ctx = exports.sourceContext(e.getId(), "JRN-90001");
        assertEquals("general_ledger.csv", ctx.file());
        assertTrue(ctx.rows().size() >= 3, "context should include surrounding rows");
        assertTrue(ctx.rows().stream().anyMatch(ExportController.ContextRow::flagged));
        assertTrue(ctx.rows().stream().anyMatch(r -> !r.flagged()), "neighbours are included");
        // rows are contiguous and ordered by source row
        for (int i = 1; i < ctx.rows().size(); i++) {
            assertTrue(ctx.rows().get(i).sourceRow() >= ctx.rows().get(i - 1).sourceRow());
        }

        // ---- Excel register parses back with one row per exception ----
        byte[] xlsx = exports.exceptionsXlsx(e.getId()).getBody();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertEquals("Rule", sheet.getRow(0).getCell(0).getStringCellValue());
            assertTrue(sheet.getLastRowNum() >= 20, "exception rows present");
        }

        // ---- PDF renders from the stored workpaper HTML ----
        var wp = wpRepo.findByEngagementIdOrderByVersionDesc(e.getId()).get(0);
        byte[] pdf = exports.workpaperPdf(wp.getId()).getBody();
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4));

        // ---- Audit File Pack contains the full reviewable set ----
        byte[] zip = exports.auditPack(e.getId()).getBody();
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertTrue(names.contains("workpaper-v1.html"));
        assertTrue(names.contains("workpaper-v1.pdf"));
        assertTrue(names.contains("exception-register.xlsx"));
        assertTrue(names.contains("import-manifest.json"));
        assertTrue(names.contains("methodology.json"));
        assertTrue(names.contains("gst-correction-schedule.csv")); // full-suite default includes GST
    }
}
