package com.ledgerintegrity.platform.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One AI-drafted note (guide §12): every output is stored with the model and prompt
 * version that produced it, labelled a draft, and attributed to the requesting user.
 * The AI layer never changes data, scores or conclusions — it only drafts language.
 */
@Entity
@Table(name = "ai_notes", indexes = @Index(columnList = "subjectType, subjectId"))
public class AiNote {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    /** EXCEPTION or CASE. */
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private String subjectType;

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String promptVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String output;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected AiNote() {} // JPA

    public AiNote(UUID id, UUID engagementId, String subjectType, UUID subjectId,
                  String model, String promptVersion, String output, String createdBy, Instant createdAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.model = model;
        this.promptVersion = promptVersion;
        this.output = output;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getOutput() { return output; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
