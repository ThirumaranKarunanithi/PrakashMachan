package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.SampleSelection;
import com.ledgerintegrity.platform.rules.persist.SampleSelectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * JET-008 / BEN-013: risk-ranked and random control sample selection.
 * Random selection is seeded and drawn over a sorted base order, so the same
 * seed on the same population always reproduces the same sample.
 */
@Service
public class SamplingService {

    private final LedgerEntryRepository entries;
    private final ExceptionCaseRepository exceptions;
    private final InvestigationCaseRepository cases;
    private final SampleSelectionRepository samples;

    public SamplingService(LedgerEntryRepository entries,
                           ExceptionCaseRepository exceptions,
                           InvestigationCaseRepository cases,
                           SampleSelectionRepository samples) {
        this.entries = entries;
        this.exceptions = exceptions;
        this.cases = cases;
        this.samples = samples;
    }

    @Transactional
    public SampleSelection select(UUID engagementId, SampleSelection.Method method, int size,
                                  Long seed, String selectedBy) {
        if (size < 1 || size > 500) throw new IllegalArgumentException("Sample size must be between 1 and 500.");
        if (selectedBy == null || selectedBy.isBlank()) throw new IllegalArgumentException("selectedBy is required.");

        List<String> populationIds = entries.findByEngagementId(engagementId).stream()
                .map(LedgerEntry::toRow)
                .map(r -> r.voucherId())
                .distinct()
                .sorted()
                .toList();
        if (populationIds.isEmpty()) throw new IllegalArgumentException("No population imported yet.");

        List<String> chosen;
        Long usedSeed = null;
        if (method == SampleSelection.Method.RISK_RANKED) {
            // vouchers involved in open exceptions, ordered by their case's effective priority
            Map<UUID, InvestigationCase> caseById = new HashMap<>();
            cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(engagementId)
                    .forEach(c -> caseById.put(c.getId(), c));
            record Ranked(String voucher, int priority, long exposure) {}
            List<Ranked> ranked = new ArrayList<>();
            Set<String> populationSet = Set.copyOf(populationIds);
            Set<String> seen = new LinkedHashSet<>();
            for (ExceptionCase x : exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(engagementId)) {
                boolean open = switch (x.getStatus()) {
                    case NEW, UNDER_REVIEW, INFO_REQUIRED -> true;
                    default -> false;
                };
                if (!open) continue;
                InvestigationCase c = x.getCaseId() == null ? null : caseById.get(x.getCaseId());
                int priority = c == null ? 0 : c.effectivePriority();
                for (String token : x.getVoucherIds().split(" ")) {
                    if (populationSet.contains(token) && seen.add(token)) {
                        ranked.add(new Ranked(token, priority, x.getExposurePaise()));
                    }
                }
            }
            ranked.sort((a, b) -> a.priority() != b.priority()
                    ? Integer.compare(b.priority(), a.priority())
                    : Long.compare(b.exposure(), a.exposure()));
            chosen = ranked.stream().map(Ranked::voucher).limit(size).toList();
            if (chosen.isEmpty()) throw new IllegalArgumentException("No open exceptions to rank a sample from.");
        } else {
            usedSeed = seed == null ? 20260819L : seed;
            List<String> shuffled = new ArrayList<>(populationIds);
            Collections.shuffle(shuffled, new Random(usedSeed));
            chosen = shuffled.subList(0, Math.min(size, shuffled.size()));
        }

        SampleSelection selection = new SampleSelection(UUID.randomUUID(), engagementId, method,
                chosen.size(), usedSeed, String.join(" ", chosen), selectedBy.trim(), Instant.now());
        samples.save(selection);
        return selection;
    }

    public List<SampleSelection> list(UUID engagementId) {
        return samples.findByEngagementIdOrderByCreatedAtDesc(engagementId);
    }
}
