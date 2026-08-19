package com.ledgerintegrity.platform.auth.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One platform user, belonging to exactly one firm (SEC-003 role-based access). */
@Entity
@Table(name = "app_users", indexes = @Index(columnList = "firmId"))
public class AppUser {

    /** CLIENT users belong to one engagement's evidence portal only (CDC-002). */
    public enum Role { ADMIN, PARTNER, MANAGER, ASSOCIATE, CLIENT }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash — plain passwords are never stored. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    // varchar, not a native enum type — enum values grow over time and H2/Postgres
    // enum columns reject new constants without a migration
    @Column(nullable = false, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    private Role role;

    /** For CLIENT users: the single engagement whose portal they may access. Null for firm staff. */
    private UUID engagementId;

    @Column(nullable = false)
    private Instant createdAt;

    protected AppUser() {} // JPA

    public AppUser(UUID id, UUID firmId, String email, String passwordHash,
                   String displayName, Role role, Instant createdAt) {
        this(id, firmId, email, passwordHash, displayName, role, null, createdAt);
    }

    public AppUser(UUID id, UUID firmId, String email, String passwordHash,
                   String displayName, Role role, UUID engagementId, Instant createdAt) {
        this.id = id;
        this.firmId = firmId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.engagementId = engagementId;
        this.createdAt = createdAt;
    }

    public UUID getEngagementId() { return engagementId; }

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
