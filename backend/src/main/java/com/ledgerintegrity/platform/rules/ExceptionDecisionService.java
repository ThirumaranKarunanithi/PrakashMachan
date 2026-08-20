package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionDecision;
import com.ledgerintegrity.platform.rules.persist.ExceptionDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The single write path for exception status changes: every transition — manual
 * decision or workflow auto-transition — appends an immutable history entry before
 * updating the exception's current state, so no change can erase a prior reason.
 */
@Service
public class ExceptionDecisionService {

    private final ExceptionCaseRepository exceptions;
    private final ExceptionDecisionRepository history;

    public ExceptionDecisionService(ExceptionCaseRepository exceptions, ExceptionDecisionRepository history) {
        this.exceptions = exceptions;
        this.history = history;
    }

    @Transactional
    public ExceptionCase transition(ExceptionCase e, ExceptionCase.Status to, String note, String actor) {
        Instant now = Instant.now();
        history.save(new ExceptionDecision(UUID.randomUUID(), e.getEngagementId(), e.getId(),
                e.getStatus(), to, note, actor, now));
        e.decide(to, note, actor, now);
        return exceptions.save(e);
    }

    public List<ExceptionDecision> historyOf(UUID exceptionId) {
        return history.findByExceptionIdOrderByDecidedAtAsc(exceptionId);
    }
}
