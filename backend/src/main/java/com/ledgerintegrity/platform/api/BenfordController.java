package com.ledgerintegrity.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.benford.BenfordService;
import com.ledgerintegrity.platform.benford.persist.BenfordRun;
import com.ledgerintegrity.platform.benford.persist.BenfordRunRepository;
import com.ledgerintegrity.platform.rules.Voucher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BenfordController {

    private final BenfordService service;
    private final BenfordRunRepository runs;
    private final TenantGuard guard;
    private final ObjectMapper objectMapper;

    public BenfordController(BenfordService service, BenfordRunRepository runs,
                             TenantGuard guard, ObjectMapper objectMapper) {
        this.service = service;
        this.runs = runs;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    public record RunRequest(BenfordRun.Population population, BenfordRun.DigitTest digitTest,
                             Boolean overrideSuitability, String overrideReason) {}

    public record RunDto(String id, String population, String digitTest, Instant executedAt,
                         int eligibleCount, long eligibleValuePaise,
                         int excludedZeros, int excludedNegatives, int excludedReversals,
                         String suitability, String suitabilityReasons,
                         boolean suitabilityOverridden, String overrideReason,
                         Double mad, String conformity, JsonNode result, String createdExceptionId,
                         String paramsJson) {}

    public record DrillRow(String voucherId, LocalDate txnDate, String userId, long amountPaise,
                           String narration, String sourceRefs) {}

    @PostMapping("/engagements/{id}/benford-runs")
    public RunDto run(@PathVariable UUID id, @RequestBody RunRequest req) {
        guard.engagement(id);
        if (req.population() == null || req.digitTest() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "population and digitTest are required (BEN-001).");
        }
        try {
            var outcome = service.run(id, req.population(), req.digitTest(),
                    Boolean.TRUE.equals(req.overrideSuitability()), req.overrideReason());
            return toDto(outcome.run());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/engagements/{id}/benford-runs")
    public List<RunDto> list(@PathVariable UUID id) {
        guard.engagement(id);
        return runs.findByEngagementIdOrderByExecutedAtDesc(id).stream().map(this::toDto).toList();
    }

    /** BEN-006: exact contributing source transactions for one digit bucket. */
    @GetMapping("/benford-runs/{runId}/drilldown")
    public List<DrillRow> drilldown(@PathVariable UUID runId, @RequestParam String digit) {
        BenfordRun run = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId));
        guard.engagement(run.getEngagementId());
        return service.drilldown(run, digit).stream()
                .map(v -> new DrillRow(v.id(), v.txnDate(), v.userId(), v.amountPaise(),
                        v.narration(), v.sourceRefs()))
                .toList();
    }

    public record CompareDto(boolean comparable, String warning, Double madDelta,
                             java.util.List<java.util.Map<String, Object>> digitDeltas) {}

    /** BEN-008: compare two runs of the same population/test; warn when not comparable. */
    @GetMapping("/benford-runs/{runId}/compare/{otherId}")
    public CompareDto compare(@PathVariable UUID runId, @PathVariable UUID otherId) {
        BenfordRun a = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + runId));
        BenfordRun b = runs.findById(otherId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + otherId));
        guard.engagement(a.getEngagementId());
        guard.engagement(b.getEngagementId());
        boolean samePopulation = a.getPopulation() == b.getPopulation() && a.getDigitTest() == b.getDigitTest();
        double ratio = b.getEligibleCount() == 0 ? 0 : (double) a.getEligibleCount() / b.getEligibleCount();
        boolean sizeComparable = ratio >= 0.5 && ratio <= 2.0;
        boolean comparable = samePopulation && sizeComparable;
        String warning = comparable ? null
                : !samePopulation ? "Different population or digit test — the comparison is not methodologically valid."
                : "Population sizes differ by more than 2x — treat differences with caution.";
        java.util.List<java.util.Map<String, Object>> deltas = new java.util.ArrayList<>();
        try {
            var ba = objectMapper.readTree(a.getResultJson()).get("buckets");
            var bb = objectMapper.readTree(b.getResultJson()).get("buckets");
            java.util.Map<String, Double> other = new java.util.HashMap<>();
            if (bb != null) bb.forEach(n -> other.put(n.get("digit").asText(), n.get("observedPct").asDouble()));
            if (ba != null) ba.forEach(n -> {
                String digit = n.get("digit").asText();
                double cur = n.get("observedPct").asDouble();
                Double prev = other.get(digit);
                deltas.add(java.util.Map.of("digit", digit, "currentPct", cur,
                        "priorPct", prev == null ? 0.0 : prev,
                        "deltaPct", Math.round((cur - (prev == null ? 0.0 : prev)) * 100) / 100.0));
            });
        } catch (Exception ignored) { }
        Double madDelta = a.getMad() == null || b.getMad() == null ? null
                : Math.round((a.getMad() - b.getMad()) * 10000) / 10000.0;
        return new CompareDto(comparable, warning, madDelta, deltas);
    }

    private RunDto toDto(BenfordRun r) {
        JsonNode result;
        try {
            result = objectMapper.readTree(r.getResultJson());
        } catch (Exception e) {
            result = objectMapper.createObjectNode();
        }
        return new RunDto(r.getId().toString(), r.getPopulation().name(), r.getDigitTest().name(),
                r.getExecutedAt(), r.getEligibleCount(), r.getEligibleValuePaise(),
                r.getExcludedZeros(), r.getExcludedNegatives(), r.getExcludedReversals(),
                r.getSuitability().name(), r.getSuitabilityReasons(),
                r.isSuitabilityOverridden(), r.getOverrideReason(),
                r.getMad(), r.getConformity().name(), result,
                r.getCreatedExceptionId() == null ? null : r.getCreatedExceptionId().toString(),
                r.getParamsJson());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
