package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns findings into persisted exceptions — the single write-path for every risk
 * module (rule engine, GST reconciliation, future bank module). Idempotent on the
 * finding identity (rule + vouchers): once raised, a finding is never duplicated,
 * whatever its review status. Always re-consolidates cases afterwards (XC-05).
 */
@Service
public class ExceptionService {

    public record RaiseResult(List<ExceptionCase> created, int skipped) {}

    private final ExceptionCaseRepository exceptions;
    private final ConsolidationService consolidation;

    public ExceptionService(ExceptionCaseRepository exceptions, ConsolidationService consolidation) {
        this.exceptions = exceptions;
        this.consolidation = consolidation;
    }

    @Transactional
    public RaiseResult raise(UUID engagementId, UUID originRunId, List<Finding> findings) {
        Set<String> existing = new HashSet<>(exceptions.findIdentityHashes(engagementId));
        List<ExceptionCase> created = new ArrayList<>();
        int skipped = 0;
        Instant now = Instant.now();
        for (Finding f : findings) {
            String identity = Checksums.sha256Hex(f.ruleId() + "|" + String.join("|", f.voucherIds()));
            if (!existing.add(identity)) { skipped++; continue; }
            created.add(ExceptionCase.from(f, engagementId, originRunId, identity, now));
        }
        exceptions.saveAll(created);
        consolidation.consolidate(engagementId);
        return new RaiseResult(created, skipped);
    }
}
