package com.ledgerintegrity.platform.auth.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FirmRepository extends JpaRepository<Firm, UUID> {
    Optional<Firm> findByNameIgnoreCase(String name);
}
