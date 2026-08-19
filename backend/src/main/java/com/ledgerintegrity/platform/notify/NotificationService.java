package com.ledgerintegrity.platform.notify;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import com.ledgerintegrity.platform.notify.persist.Notification;
import com.ledgerintegrity.platform.notify.persist.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * §18.3 notifications, in-app (email transport is a deployment concern):
 *  - NFR-002: owners are notified of new high-priority cases, overdue evidence, pending review
 *  - NFR-003: identity-hashed dedupe — the same unresolved event never alerts twice
 *  - NFR-001: messages carry engagement names and counts, not transaction detail dumps
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final EngagementRepository engagements;
    private final EvidenceRequestRepository evidenceRequests;

    public NotificationService(NotificationRepository notifications,
                               EngagementRepository engagements,
                               EvidenceRequestRepository evidenceRequests) {
        this.notifications = notifications;
        this.engagements = engagements;
        this.evidenceRequests = evidenceRequests;
    }

    /** Create at most once per (firm, identity seed). Never breaks the calling flow. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyOnce(UUID firmId, String type, String identitySeed, String message, String link) {
        try {
            String hash = Checksums.sha256Hex(type + "|" + identitySeed);
            if (notifications.existsByFirmIdAndIdentityHash(firmId, hash)) return;
            notifications.save(new Notification(UUID.randomUUID(), firmId, hash, type,
                    message.length() > 500 ? message.substring(0, 499) : message, link, Instant.now()));
        } catch (Exception ignored) {
            // notifying must never fail the business operation
        }
    }

    /** CDC-005: overdue evidence requests surface as notifications, once each. */
    @Transactional
    public void scanOverdueEvidence(UUID firmId) {
        LocalDate today = LocalDate.now();
        for (Engagement e : engagements.findByFirmIdOrderByCreatedAtDesc(firmId)) {
            for (EvidenceRequest r : evidenceRequests.findByEngagementIdOrderByCreatedAtDesc(e.getId())) {
                if (!r.isOverdue(today)) continue;
                notifyOnce(firmId, "EVIDENCE_OVERDUE", r.getId().toString(),
                        "Evidence request \"" + r.getTitle() + "\" for " + e.getClientName()
                                + " is overdue (due " + r.getDueDate() + ").", null);
            }
        }
    }

    @Transactional
    public List<Notification> list(UUID firmId) {
        scanOverdueEvidence(firmId);
        return notifications.findTop50ByFirmIdOrderByCreatedAtDesc(firmId);
    }

    public long unreadCount(UUID firmId) {
        return notifications.countByFirmIdAndReadAtIsNull(firmId);
    }

    @Transactional
    public void markAllRead(UUID firmId) {
        Instant now = Instant.now();
        List<Notification> unread = notifications.findByFirmIdAndReadAtIsNull(firmId);
        unread.forEach(n -> n.markRead(now));
        notifications.saveAll(unread);
    }
}
