package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExceptionDecisionRepository extends JpaRepository<ExceptionDecision, UUID> {
    List<ExceptionDecision> findByExceptionIdOrderByDecidedAtAsc(UUID exceptionId);
}
