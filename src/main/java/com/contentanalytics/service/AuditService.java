package com.contentanalytics.service;

import com.contentanalytics.entity.AuditLog;
import com.contentanalytics.entity.User;
import com.contentanalytics.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Writes security-relevant and billing events to an append-only audit trail.
 * <p>
 * Logging is deliberately best-effort: a failure to record an audit entry must never break
 * the business operation that triggered it, hence {@code REQUIRES_NEW} and a caught exception.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(User user, String username, String action,
                          String entityType, String entityId,
                          String details, String ipAddress, String userAgent) {
        try {
            AuditLog entry = AuditLog.builder()
                    .user(user)
                    .username(username)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details)
                    .ipAddress(truncate(ipAddress, 45))
                    .userAgent(truncate(userAgent, 500))
                    .build();

            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log for action {}", action, e);
        }
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getUserHistory(UUID userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);

        return auditLogRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(safePage, safeSize))
                .getContent();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

}