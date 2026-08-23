package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfig;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * XC-05 / RSK-002: consolidate related exceptions into one investigation case.
 *
 * MVP relationship: shared vouchers — exceptions form a graph where an edge exists
 * when they touch a common voucher; each connected component becomes one case.
 * (Later phases add vendor / user / bank-account / document relationships.)
 *
 * Idempotent and merge-stable: re-running keeps existing case identity where possible;
 * when components grow together, members move to the OLDEST case and emptied cases
 * are removed, so review history stays attached to the surviving case.
 */
@Service
public class ConsolidationService {

    private final ExceptionCaseRepository exceptions;
    private final InvestigationCaseRepository cases;
    private final EngagementRepository engagements;
    private final RiskWeightConfigRepository weightConfigs;
    private final com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository evidenceRequests;

    public ConsolidationService(ExceptionCaseRepository exceptions, InvestigationCaseRepository cases,
                                EngagementRepository engagements, RiskWeightConfigRepository weightConfigs,
                                com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository evidenceRequests) {
        this.exceptions = exceptions;
        this.cases = cases;
        this.engagements = engagements;
        this.weightConfigs = weightConfigs;
        this.evidenceRequests = evidenceRequests;
    }

    /** Compact, ordered breakdown: {"family": {"score": n, "cap": c}, ...} */
    private static String familyJson(Map<RiskFamily, Integer> byFamily, Map<RiskFamily, Integer> caps) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (RiskFamily f : RiskFamily.values()) {
            Integer v = byFamily.get(f);
            if (v == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(f.name()).append("\":{\"score\":").append(v)
              .append(",\"cap\":").append(caps.get(f)).append("}");
        }
        return sb.append("}").toString();
    }

    /** RSK-003: severity weights come from the firm methodology owner; defaults are illustrative. */
    private Map<Finding.Severity, Integer> weightsFor(UUID engagementId) {
        return engagements.findById(engagementId)
                .flatMap(e -> weightConfigs.findTopByFirmIdOrderByVersionDesc(e.getFirmId()))
                .map(c -> Map.of(Finding.Severity.HIGH, c.getHighWeight(),
                        Finding.Severity.MEDIUM, c.getMediumWeight(),
                        Finding.Severity.LOW, c.getLowWeight()))
                .orElse(Map.of(Finding.Severity.HIGH, RiskWeightConfig.DEFAULT_HIGH,
                        Finding.Severity.MEDIUM, RiskWeightConfig.DEFAULT_MEDIUM,
                        Finding.Severity.LOW, RiskWeightConfig.DEFAULT_LOW));
    }

    /** Family caps from the firm methodology config; guide §9.1 defaults otherwise. */
    private Map<RiskFamily, Integer> capsFor(UUID engagementId) {
        return engagements.findById(engagementId)
                .flatMap(e -> weightConfigs.findTopByFirmIdOrderByVersionDesc(e.getFirmId()))
                .map(c -> Map.of(
                        RiskFamily.RECONCILIATION, c.getReconciliationCap(),
                        RiskFamily.DETERMINISTIC, c.getDeterministicCap(),
                        RiskFamily.BEHAVIOUR_ACCESS, c.getBehaviourCap(),
                        RiskFamily.STATISTICAL, c.getStatisticalCap(),
                        RiskFamily.RELATIONSHIP, c.getRelationshipCap(),
                        RiskFamily.EVIDENCE, c.getEvidenceCap()))
                .orElse(Map.of(
                        RiskFamily.RECONCILIATION, RiskWeightConfig.DEFAULT_RECONCILIATION_CAP,
                        RiskFamily.DETERMINISTIC, RiskWeightConfig.DEFAULT_DETERMINISTIC_CAP,
                        RiskFamily.BEHAVIOUR_ACCESS, RiskWeightConfig.DEFAULT_BEHAVIOUR_CAP,
                        RiskFamily.STATISTICAL, RiskWeightConfig.DEFAULT_STATISTICAL_CAP,
                        RiskFamily.RELATIONSHIP, RiskWeightConfig.DEFAULT_RELATIONSHIP_CAP,
                        RiskFamily.EVIDENCE, RiskWeightConfig.DEFAULT_EVIDENCE_CAP));
    }

    /**
     * Review Priority Score v2 (guide §9): raw severity points accumulate PER FAMILY,
     * each family is capped, and the total is the sum of the capped families (0-100
     * when the caps follow the 25/25/15/10/15/10 structure). Evidence risk counts
     * overdue or rejected evidence requests attached to the case's members.
     * Returns the per-family breakdown; the total is the values' sum.
     */
    static Map<RiskFamily, Integer> familyScores(List<ExceptionCase> members,
                                          Map<Finding.Severity, Integer> severityWeight,
                                          Map<RiskFamily, Integer> caps,
                                          int overdueOrRejectedEvidence) {
        Map<RiskFamily, Integer> raw = new java.util.EnumMap<>(RiskFamily.class);
        for (ExceptionCase m : members) {
            raw.merge(RiskFamily.of(m.getRuleId()), severityWeight.get(m.getSeverity()), Integer::sum);
        }
        raw.merge(RiskFamily.EVIDENCE, overdueOrRejectedEvidence * 5, Integer::sum);
        Map<RiskFamily, Integer> capped = new java.util.EnumMap<>(RiskFamily.class);
        for (Map.Entry<RiskFamily, Integer> e : raw.entrySet()) {
            if (e.getValue() == 0) continue;
            capped.put(e.getKey(), Math.min(e.getValue(), caps.get(e.getKey())));
        }
        return capped;
    }

    @Transactional
    public List<InvestigationCase> consolidate(UUID engagementId) {
        Map<Finding.Severity, Integer> severityWeight = weightsFor(engagementId);
        Map<RiskFamily, Integer> familyCaps = capsFor(engagementId);
        List<ExceptionCase> all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(engagementId);
        if (all.isEmpty()) return List.of();

        // union-find over voucher ids
        Map<String, String> parent = new HashMap<>();
        for (ExceptionCase e : all) {
            List<String> vouchers = List.of(e.getVoucherIds().split(" "));
            for (String v : vouchers) parent.putIfAbsent(v, v);
            for (int i = 1; i < vouchers.size(); i++) union(parent, vouchers.get(0), vouchers.get(i));
        }

        // group exceptions by component
        Map<String, List<ExceptionCase>> components = new LinkedHashMap<>();
        for (ExceptionCase e : all) {
            String root = find(parent, e.getVoucherIds().split(" ")[0]);
            components.computeIfAbsent(root, k -> new ArrayList<>()).add(e);
        }

        Map<UUID, InvestigationCase> existingById = new HashMap<>();
        cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(engagementId)
                .forEach(c -> existingById.put(c.getId(), c));
        int nextCaseNo = existingById.values().stream().mapToInt(InvestigationCase::getCaseNo).max().orElse(0) + 1;

        Instant now = Instant.now();
        Set<UUID> liveCaseIds = new LinkedHashSet<>();
        List<InvestigationCase> result = new ArrayList<>();

        for (List<ExceptionCase> members : components.values()) {
            // survivor = oldest existing case among members, else a new case
            InvestigationCase survivor = members.stream()
                    .map(ExceptionCase::getCaseId)
                    .filter(java.util.Objects::nonNull)
                    .map(existingById::get)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.comparing(InvestigationCase::getCreatedAt))
                    .orElse(null);
            if (survivor == null) {
                survivor = new InvestigationCase(UUID.randomUUID(), engagementId, nextCaseNo++, now);
                existingById.put(survivor.getId(), survivor);
            }
            liveCaseIds.add(survivor.getId());

            for (ExceptionCase e : members) {
                if (!survivor.getId().equals(e.getCaseId())) e.setCaseId(survivor.getId());
            }

            // aggregates (members arrive severity-ranked from the repository query)
            ExceptionCase top = members.get(0);
            Finding.Severity severity = members.stream()
                    .map(ExceptionCase::getSeverity)
                    .min(Comparator.comparingInt(Enum::ordinal)) // HIGH has the lowest ordinal
                    .orElse(Finding.Severity.LOW);
            int evidenceSignals = (int) evidenceRequests
                    .findByEngagementIdOrderByCreatedAtDesc(engagementId).stream()
                    .filter(r -> members.stream().anyMatch(m -> m.getId().equals(r.getExceptionId())))
                    .filter(r -> r.isOverdue(java.time.LocalDate.now())
                            || r.getStatus() == com.ledgerintegrity.platform.evidence.persist.EvidenceRequest.Status.REJECTED)
                    .count();
            Map<RiskFamily, Integer> byFamily =
                    familyScores(members, severityWeight, familyCaps, evidenceSignals);
            int score = byFamily.values().stream().mapToInt(Integer::intValue).sum();
            long exposure = members.stream().mapToLong(ExceptionCase::getExposurePaise).max().orElse(0);
            Set<String> vouchers = new TreeSet<>();
            members.forEach(m -> vouchers.addAll(List.of(m.getVoucherIds().split(" "))));
            String title = String.join(" ", vouchers) + " — " + top.getRuleName()
                    + (members.size() > 1 ? " (+" + (members.size() - 1) + " related signal(s))" : "");
            survivor.updateAggregates(truncate(title, 300), severity, score, exposure,
                    truncate(String.join(" ", vouchers), 1000), now);
            survivor.setFamilyScoresJson(familyJson(byFamily, familyCaps));

            cases.save(survivor);
            result.add(survivor);
        }
        exceptions.saveAll(all);

        // remove cases whose members all moved elsewhere
        for (InvestigationCase c : existingById.values()) {
            if (!liveCaseIds.contains(c.getId())) cases.delete(c);
        }

        result.sort(Comparator.comparingInt(InvestigationCase::getPriorityScore).reversed()
                .thenComparing(Comparator.comparingLong(InvestigationCase::getExposurePaise).reversed()));
        return result;
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        // path compression
        String cur = x;
        while (!parent.get(cur).equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
