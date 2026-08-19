package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.RuleRun;
import com.ledgerintegrity.platform.rules.persist.RuleRunRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RuleController {

    private final RuleEngineService engine;
    private final RuleRunRepository runs;
    private final ExceptionCaseRepository exceptions;
    private final InvestigationCaseRepository cases;
    private final TenantGuard guard;
    private final com.ledgerintegrity.platform.rules.SamplingService samplingService;
    private final com.ledgerintegrity.platform.rules.CaseTimelineService timelineService;

    public RuleController(RuleEngineService engine, RuleRunRepository runs,
                          ExceptionCaseRepository exceptions, InvestigationCaseRepository cases,
                          TenantGuard guard,
                          com.ledgerintegrity.platform.rules.SamplingService samplingService,
                          com.ledgerintegrity.platform.rules.CaseTimelineService timelineService) {
        this.samplingService = samplingService;
        this.timelineService = timelineService;
        this.engine = engine;
        this.runs = runs;
        this.exceptions = exceptions;
        this.cases = cases;
        this.guard = guard;
    }

    // ---------- DTOs ----------

    public record RunRequest(List<String> privilegedUsers,
                             java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                             List<String> voucherTypes, List<String> users, Long minAmountRupees) {}

    public record RuleRunDto(String id, String packVersion, Instant executedAt, String paramsJson,
                             int populationVouchers, long populationValuePaise,
                             int findings, int exceptionsCreated, int skippedExisting) {
        static RuleRunDto from(RuleRun r) {
            return new RuleRunDto(r.getId().toString(), r.getPackVersion(), r.getExecutedAt(), r.getParamsJson(),
                    r.getPopulationVouchers(), r.getPopulationValuePaise(),
                    r.getFindings(), r.getExceptionsCreated(), r.getSkippedExisting());
        }
    }

    public record ExceptionDto(String id, String caseId, String ruleId, String ruleName, String severity,
                               long exposurePaise, String reason, String voucherIds, String sourceRefs,
                               String status, String decisionNote, String decidedBy, Instant decidedAt,
                               Instant createdAt) {
        static ExceptionDto from(ExceptionCase e) {
            return new ExceptionDto(e.getId().toString(),
                    e.getCaseId() == null ? null : e.getCaseId().toString(),
                    e.getRuleId(), e.getRuleName(),
                    e.getSeverity().name(), e.getExposurePaise(), e.getReason(), e.getVoucherIds(),
                    e.getSourceRefs(), e.getStatus().name(), e.getDecisionNote(), e.getDecidedBy(),
                    e.getDecidedAt(), e.getCreatedAt());
        }
    }

    public record CaseDto(String id, int caseNo, String title, String severity, int priorityScore,
                          int effectivePriority, Integer overriddenPriority, String overrideReason, String overriddenBy,
                          long exposurePaise, String voucherIds, int exceptionCount, int openCount,
                          List<ExceptionDto> exceptions) {
        static CaseDto from(InvestigationCase c, List<ExceptionCase> members) {
            int open = (int) members.stream().filter(m -> switch (m.getStatus()) {
                case NEW, UNDER_REVIEW, INFO_REQUIRED -> true;
                default -> false;
            }).count();
            return new CaseDto(c.getId().toString(), c.getCaseNo(), c.getTitle(), c.getSeverity().name(),
                    c.getPriorityScore(), c.effectivePriority(), c.getOverriddenPriority(),
                    c.getOverrideReason(), c.getOverriddenBy(),
                    c.getExposurePaise(), c.getVoucherIds(),
                    members.size(), open, members.stream().map(ExceptionDto::from).toList());
        }
    }

    public record DecisionRequest(@NotNull ExceptionCase.Status status, String note, String decidedBy) {}

    // ---------- endpoints ----------

    @PostMapping("/engagements/{id}/rule-runs")
    public Map<String, Object> run(@PathVariable UUID id, @RequestBody(required = false) RunRequest req) {
        guard.engagement(id);
        RuleParams params = RuleParams.defaults();
        if (req != null && req.privilegedUsers() != null) {
            Set<String> users = new HashSet<>();
            req.privilegedUsers().forEach(u -> { if (u != null && !u.isBlank()) users.add(u.trim()); });
            params = params.withPrivilegedUsers(users);
        }
        RuleEngineService.PopulationFilter filter = null;
        if (req != null && (req.dateFrom() != null || req.dateTo() != null
                || (req.voucherTypes() != null && !req.voucherTypes().isEmpty())
                || (req.users() != null && !req.users().isEmpty())
                || req.minAmountRupees() != null)) {
            filter = new RuleEngineService.PopulationFilter(req.dateFrom(), req.dateTo(),
                    req.voucherTypes() == null ? null : new HashSet<>(req.voucherTypes()),
                    req.users() == null ? null : new HashSet<>(req.users()),
                    req.minAmountRupees() == null ? null : req.minAmountRupees() * 100);
        }
        try {
            RuleEngineService.RunResult result = engine.run(id, params, filter);
            return Map.of(
                    "run", RuleRunDto.from(result.run()),
                    "created", result.created().stream().map(ExceptionDto::from).toList(),
                    "caseCount", result.cases().size());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Consolidated view (BRD §17.2): one case per underlying event, priority-ranked. */
    @GetMapping("/engagements/{id}/cases")
    public List<CaseDto> listCases(@PathVariable UUID id) {
        guard.engagement(id);
        var members = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(id);
        Map<UUID, List<ExceptionCase>> byCase = new java.util.HashMap<>();
        for (ExceptionCase e : members) {
            if (e.getCaseId() != null) byCase.computeIfAbsent(e.getCaseId(), k -> new java.util.ArrayList<>()).add(e);
        }
        return cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(id).stream()
                .map(c -> CaseDto.from(c, byCase.getOrDefault(c.getId(), List.of())))
                // RSK-004: the review queue is ordered by the EFFECTIVE priority
                .sorted(java.util.Comparator.comparingInt(CaseDto::effectivePriority).reversed())
                .toList();
    }

    @GetMapping("/engagements/{id}/rule-runs")
    public List<RuleRunDto> listRuns(@PathVariable UUID id) {
        guard.engagement(id);
        return runs.findByEngagementIdOrderByExecutedAtDesc(id).stream().map(RuleRunDto::from).toList();
    }

    @GetMapping("/engagements/{id}/exceptions")
    public List<ExceptionDto> listExceptions(@PathVariable UUID id, @RequestParam(required = false) String status) {
        guard.engagement(id);
        var all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(id);
        return all.stream()
                .filter(e -> status == null || e.getStatus().name().equalsIgnoreCase(status))
                .map(ExceptionDto::from)
                .toList();
    }

    /** Exception-register export for schedules and review packs. */
    @GetMapping(value = "/engagements/{id}/exceptions.csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> exceptionsCsv(@PathVariable UUID id) {
        guard.engagement(id);
        var rows = new java.util.ArrayList<List<String>>();
        for (var x : exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(id)) {
            rows.add(List.of(x.getRuleId(), x.getRuleName(), x.getSeverity().name(),
                    String.format("%.2f", x.getExposurePaise() / 100.0), x.getStatus().name(),
                    x.getVoucherIds(), x.getReason(),
                    x.getDecisionNote() == null ? "" : x.getDecisionNote(),
                    x.getDecidedBy() == null ? "" : x.getDecidedBy()));
        }
        String csv = com.ledgerintegrity.platform.common.Csv.serialize(
                List.of("rule_id", "rule", "severity", "exposure_inr", "status", "vouchers",
                        "reason", "decision_note", "decided_by"), rows);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=exception-register.csv")
                .body(csv);
    }

    public record SampleRequest(String method, Integer size, Long seed, String selectedBy) {}

    public record SampleDto(String id, String method, int sampleSize, Long seed, String voucherIds,
                            String selectedBy, Instant createdAt) {}

    /** JET-008 / BEN-013: risk-ranked and seeded-random samples, documented in the workpaper. */
    @PostMapping("/engagements/{id}/samples")
    public SampleDto selectSample(@PathVariable UUID id, @RequestBody SampleRequest req) {
        guard.engagement(id);
        try {
            var method = com.ledgerintegrity.platform.rules.persist.SampleSelection.Method
                    .valueOf(req.method() == null ? "RANDOM" : req.method().toUpperCase());
            var s = samplingService.select(id, method, req.size() == null ? 25 : req.size(),
                    req.seed(), req.selectedBy());
            return new SampleDto(s.getId().toString(), s.getMethod(), s.getSampleSize(), s.getSeed(),
                    s.getVoucherIds(), s.getSelectedBy(), s.getCreatedAt());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/engagements/{id}/samples")
    public List<SampleDto> listSamples(@PathVariable UUID id) {
        guard.engagement(id);
        return samplingService.list(id).stream()
                .map(s -> new SampleDto(s.getId().toString(), s.getMethod(), s.getSampleSize(), s.getSeed(),
                        s.getVoucherIds(), s.getSelectedBy(), s.getCreatedAt()))
                .toList();
    }

    /** AC-08: the chronological story of one case across every source. */
    @GetMapping("/cases/{id}/timeline")
    public List<com.ledgerintegrity.platform.rules.CaseTimelineService.TimelineEvent> timeline(@PathVariable UUID id) {
        var c = guard.investigationCase(id);
        return timelineService.timeline(c);
    }

    public record PriorityOverrideRequest(Integer priority, String reason, String reviewer) {}

    /** RSK-004: a reviewer changes a case's review priority without touching rule results. */
    @PatchMapping("/cases/{id}/priority")
    public CaseDto overridePriority(@PathVariable UUID id, @RequestBody PriorityOverrideRequest req) {
        var c = guard.investigationCase(id);
        try {
            c.overridePriority(req.priority(), req.reason(), req.reviewer(), Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        cases.save(c);
        var members = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(c.getEngagementId())
                .stream().filter(x -> c.getId().equals(x.getCaseId())).toList();
        return CaseDto.from(c, members);
    }

    /**
     * Record the auditor's decision on an exception (BRD §3.3). Decision states require
     * a documented reason — closure without a professional decision is not allowed (CDC-006).
     */
    @PatchMapping("/exceptions/{id}")
    public ExceptionDto decide(@PathVariable UUID id, @RequestBody DecisionRequest req) {
        ExceptionCase e = guard.exception(id);
        if (req.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        boolean decisionState = switch (req.status()) {
            case EXPLAINED, CONFIRMED, NOT_APPLICABLE, ESCALATED, CLOSED -> true;
            case NEW, UNDER_REVIEW, INFO_REQUIRED -> false;
        };
        if (decisionState && (req.note() == null || req.note().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A documented reason is required for status " + req.status());
        }
        e.decide(req.status(), req.note(), req.decidedBy(), Instant.now());
        exceptions.save(e);
        return ExceptionDto.from(e);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
