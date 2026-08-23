package com.ledgerintegrity.platform.workpaper;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wptestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class WorkpaperIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired RuleEngineService engine;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired WorkpaperService service;
    @Autowired WorkpaperRepository workpapers;
    @Autowired MappingProfileRepository profiles;

    @Test
    void generatesVersionedWorkpaperWithSignOffOrderAndLocking() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));

        // record a decision so the register carries judgement
        ExceptionCase first = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId()).get(0);
        first.decide(ExceptionCase.Status.ESCALATED, "Needs partner attention.", "MGR-AUDIT", Instant.now());
        exceptions.save(first);

        // v1: content carries checksums, pack version, params, decisions (AWP-002/003/004)
        Workpaper v1 = service.generate(e.getId());
        assertEquals(1, v1.getVersion());
        assertEquals(Workpaper.Status.DRAFT, v1.getStatus());
        String html = v1.getContentHtml();
        assertTrue(html.contains("mvp-pack-0.5.0"));
        assertTrue(html.contains("privilegedUsers"));
        assertTrue(html.contains("JRN-90001"));
        assertTrue(html.contains("Needs partner attention."));
        assertTrue(html.contains("SHA") || html.matches("(?s).*[0-9a-f]{64}.*")); // file checksums present
        assertEquals(Checksums.sha256Hex(html), v1.getContentSha256());

        // sign-off order + AWP-005 independence
        assertThrows(IllegalStateException.class, () -> service.sign(v1.getId(), Workpaper.Role.PARTNER, "P. Partner"));
        service.sign(v1.getId(), Workpaper.Role.PREPARER, "A. Associate");
        assertThrows(IllegalArgumentException.class, () -> service.sign(v1.getId(), Workpaper.Role.MANAGER, "A. Associate"));
        service.sign(v1.getId(), Workpaper.Role.MANAGER, "M. Manager");
        service.sign(v1.getId(), Workpaper.Role.PARTNER, "P. Partner");
        Workpaper signed = workpapers.findById(v1.getId()).orElseThrow();
        assertEquals(Workpaper.Status.SIGNED, signed.getStatus());

        // AWP-006: signed = locked; further signing rejected, content unchanged
        assertThrows(IllegalStateException.class, () -> service.sign(v1.getId(), Workpaper.Role.PREPARER, "X"));
        assertEquals(v1.getContentSha256(), signed.getContentSha256());

        // regeneration creates v2; v1 stays intact
        Workpaper v2 = service.generate(e.getId());
        assertEquals(2, v2.getVersion());
        assertEquals(Workpaper.Status.DRAFT, v2.getStatus());
        assertEquals(2, workpapers.findByEngagementIdOrderByVersionDesc(e.getId()).size());
        assertEquals(Workpaper.Status.SIGNED, workpapers.findById(v1.getId()).orElseThrow().getStatus());
    }
}
