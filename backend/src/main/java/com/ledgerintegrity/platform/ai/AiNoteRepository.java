package com.ledgerintegrity.platform.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiNoteRepository extends JpaRepository<AiNote, UUID> {
    Optional<AiNote> findTopBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(String subjectType, UUID subjectId);
}
