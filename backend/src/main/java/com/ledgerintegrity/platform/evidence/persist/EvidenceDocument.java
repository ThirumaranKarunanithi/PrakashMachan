package com.ledgerintegrity.platform.evidence.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;

import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded evidence file version. All versions are kept with uploader, timestamp
 * and checksum so the reviewed version is unambiguous (CDC-004).
 */
@Entity
@Table(name = "evidence_documents", indexes = @Index(columnList = "requestId"))
public class EvidenceDocument {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID requestId;

    /** 1-based version within the request */
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    // "bytea" works in both H2 (PostgreSQL mode) and real PostgreSQL; H2's PG mode rejects BLOB
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    /** SEC-004: true when content is AES-GCM encrypted at rest (flagged per row so
     *  documents stored before the key existed remain readable). */
    @Column(columnDefinition = "boolean default false not null")
    private boolean encrypted;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private String uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt;

    protected EvidenceDocument() {} // JPA

    public EvidenceDocument(UUID id, UUID requestId, int version, String fileName, String contentType,
                            byte[] content, String sha256, String uploadedBy, Instant uploadedAt) {
        this.id = id;
        this.requestId = requestId;
        this.version = version;
        this.fileName = fileName;
        this.contentType = contentType;
        this.content = content;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public int getVersion() { return version; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public byte[] getContent() { return content; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean v) { this.encrypted = v; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
