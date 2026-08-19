package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService;
import com.ledgerintegrity.platform.importer.MappingProfile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.importer.persist.ImportBatch;
import com.ledgerintegrity.platform.importer.persist.ImportBatchRepository;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class EngagementController {

    private final EngagementRepository engagements;
    private final EngagementImportService importService;
    private final ImportBatchRepository batches;
    private final LedgerEntryRepository entries;
    private final MappingProfileRepository profiles;
    private final CurrentUser currentUser;
    private final TenantGuard guard;
    private final com.ledgerintegrity.platform.engagement.EngagementDeletionService deletionService;

    public EngagementController(EngagementRepository engagements,
                                EngagementImportService importService,
                                ImportBatchRepository batches,
                                LedgerEntryRepository entries,
                                MappingProfileRepository profiles,
                                CurrentUser currentUser,
                                TenantGuard guard,
                                com.ledgerintegrity.platform.engagement.EngagementDeletionService deletionService) {
        this.deletionService = deletionService;
        this.engagements = engagements;
        this.importService = importService;
        this.batches = batches;
        this.entries = entries;
        this.profiles = profiles;
        this.currentUser = currentUser;
        this.guard = guard;
    }

    // ---------- mapping profiles ----------

    @GetMapping("/mappings")
    public List<MappingProfile> listMappings() {
        return profiles.findAll();
    }

    // ---------- engagements ----------

    public record CreateEngagementRequest(
            @NotBlank String clientName,
            @NotNull LocalDate fyStart,
            @NotNull LocalDate fyEnd,
            @NotNull LocalDate closeDate) {}

    public record EngagementDto(String id, String clientName, LocalDate fyStart, LocalDate fyEnd,
                                LocalDate closeDate, String status, Instant createdAt,
                                long populationCount, int importCount) {
        static EngagementDto from(Engagement e, long populationCount, int importCount) {
            return new EngagementDto(e.getId().toString(), e.getClientName(), e.getFyStart(), e.getFyEnd(),
                    e.getCloseDate(), e.getStatus(), e.getCreatedAt(), populationCount, importCount);
        }
    }

    public record ImportBatchDto(String id, String profile, Instant importedAt,
                                 int totalRows, int addedRows, int skippedRows, int issueCount,
                                 boolean balanced, boolean tbAgrees) {
        static ImportBatchDto from(ImportBatch b, int issueCount) {
            return new ImportBatchDto(b.getId().toString(), b.getProfileName(), b.getImportedAt(),
                    b.getTotalRows(), b.getAddedRows(), b.getSkippedRows(), issueCount,
                    b.isBalanced(), b.isTbAgrees());
        }
    }

    @PostMapping("/engagements")
    @ResponseStatus(HttpStatus.CREATED)
    public EngagementDto create(@Valid @RequestBody CreateEngagementRequest req) {
        if (req.fyEnd().isBefore(req.fyStart())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fyEnd is before fyStart");
        }
        Engagement e = new Engagement(UUID.randomUUID(), currentUser.firmId(), req.clientName().trim(),
                req.fyStart(), req.fyEnd(), req.closeDate(), Instant.now());
        engagements.save(e);
        return EngagementDto.from(e, 0, 0);
    }

    @GetMapping("/engagements")
    public List<EngagementDto> list() {
        List<EngagementDto> out = new ArrayList<>();
        for (Engagement e : engagements.findByFirmIdOrderByCreatedAtDesc(currentUser.firmId())) {
            out.add(EngagementDto.from(e,
                    entries.countByEngagementId(e.getId()),
                    batches.findByEngagementIdOrderByImportedAtDesc(e.getId()).size()));
        }
        return out;
    }

    @GetMapping("/engagements/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(@PathVariable UUID id) {
        Engagement e = guard.engagement(id);
        List<ImportBatch> history = batches.findByEngagementIdOrderByImportedAtDesc(id);
        return Map.of(
                "engagement", EngagementDto.from(e, entries.countByEngagementId(id), history.size()),
                "imports", history.stream().map(b -> ImportBatchDto.from(b, b.getIssues().size())).toList());
    }

    /** SEC-006 / CDC-008: secure deletion with an auditable completion record. ADMIN only. */
    @DeleteMapping("/engagements/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        var user = currentUser.require();
        if (user.getRole() != com.ledgerintegrity.platform.auth.persist.AppUser.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a firm ADMIN can delete an engagement.");
        }
        guard.engagement(id);
        int removed = deletionService.deleteEngagement(id, user.getFirmId(), user.getEmail());
        return Map.of("status", "deleted", "childRowsRemoved", removed);
    }

    /** JET-002: how the population classifies by source system, with confidence. */
    @GetMapping("/engagements/{id}/source-classification")
    public List<Map<String, Object>> sourceClassification(@PathVariable UUID id) {
        guard.engagement(id);
        Map<String, Long> bySource = new java.util.TreeMap<>();
        for (var entry : entries.findByEngagementId(id)) {
            var row = entry.toRow();
            bySource.merge(row.source() == null ? "(unclassified)" : row.source(), 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        bySource.forEach((source, count) -> out.add(Map.of(
                "source", source, "rows", count,
                "confidence", source.equals("(unclassified)") ? "UNKNOWN — source metadata absent" : "EXPLICIT — from source metadata")));
        return out;
    }

    // ---------- imports ----------

    @PostMapping(value = "/engagements/{id}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummaryDto importGl(@PathVariable UUID id,
                                     @RequestParam("gl") MultipartFile gl,
                                     @RequestParam("tb") MultipartFile tb,
                                     @RequestParam("mapping") String mappingName) throws IOException {
        guard.engagement(id);
        MappingProfile profile = profiles.find(mappingName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown mapping profile: " + mappingName));
        try {
            var result = importService.importInto(id,
                    new ImportService.SourceFile(fileName(gl, "general_ledger.csv"), gl.getBytes()),
                    new ImportService.SourceFile(fileName(tb, "trial_balance.csv"), tb.getBytes()),
                    profile);
            return ImportSummaryDto.from(result);
        } catch (ImportService.ProfileMismatchException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** BRD §4.1: Excel import — accepts .xlsx (or CSV) for both files, same pipeline. */
    @PostMapping(value = "/engagements/{id}/imports/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummaryDto importXlsx(@PathVariable UUID id,
                                       @RequestParam("gl") MultipartFile gl,
                                       @RequestParam("tb") MultipartFile tb,
                                       @RequestParam("mapping") String mappingName) throws IOException {
        guard.engagement(id);
        MappingProfile profile = profiles.find(mappingName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown mapping profile: " + mappingName));
        try {
            var result = importService.importXlsxInto(id,
                    new ImportService.SourceFile(fileName(gl, "general_ledger.xlsx"), gl.getBytes()),
                    new ImportService.SourceFile(fileName(tb, "trial_balance.csv"), tb.getBytes()),
                    profile);
            return ImportSummaryDto.from(result);
        } catch (ImportService.ProfileMismatchException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** BRD §4.1: Tally Daybook XML import; the trial balance CSV is optional but recommended. */
    @PostMapping(value = "/engagements/{id}/imports/tally", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummaryDto importTally(@PathVariable UUID id,
                                        @RequestParam("xml") MultipartFile xml,
                                        @RequestParam(value = "tb", required = false) MultipartFile tb) throws IOException {
        guard.engagement(id);
        try {
            var result = importService.importTallyInto(id,
                    new ImportService.SourceFile(fileName(xml, "tally.xml"), xml.getBytes()),
                    tb == null || tb.isEmpty() ? null
                            : new ImportService.SourceFile(fileName(tb, "trial_balance.csv"), tb.getBytes()));
            return ImportSummaryDto.from(result);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** DAT-003: a downloadable data-quality report is produced for every import. */
    @GetMapping(value = "/engagements/{id}/imports/{batchId}/quality-report.csv", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> qualityReportCsv(@PathVariable UUID id, @PathVariable UUID batchId) {
        guard.engagement(id);
        ImportBatch batch = batches.findById(batchId)
                .filter(b -> b.getEngagementId().equals(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown import: " + batchId));
        List<List<String>> rows = batch.getIssues().stream()
                .map(i -> List.of(i.sourceFile(), String.valueOf(i.sourceRow()), i.issueType(),
                        i.field() == null ? "" : i.field(),
                        i.value() == null ? "" : i.value(),
                        i.message()))
                .toList();
        String csv = Csv.serialize(List.of("file", "row", "issue_type", "field", "value", "message"), rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=quality-report-" + batchId + ".csv")
                .body(csv);
    }

    private static String fileName(MultipartFile f, String fallback) {
        String name = f.getOriginalFilename();
        return (name == null || name.isBlank()) ? fallback : name;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? e.getMessage() : e.getReason()));
    }
}
