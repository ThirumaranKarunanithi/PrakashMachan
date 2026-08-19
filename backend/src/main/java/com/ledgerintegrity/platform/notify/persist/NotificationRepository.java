package com.ledgerintegrity.platform.notify.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop50ByFirmIdOrderByCreatedAtDesc(UUID firmId);

    long countByFirmIdAndReadAtIsNull(UUID firmId);

    List<Notification> findByFirmIdAndReadAtIsNull(UUID firmId);

    boolean existsByFirmIdAndIdentityHash(UUID firmId, String identityHash);
}
