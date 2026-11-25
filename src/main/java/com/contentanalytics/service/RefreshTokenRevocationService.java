package com.contentanalytics.service;

import com.contentanalytics.entity.RefreshTokenStatus;
import com.contentanalytics.entity.User;
import com.contentanalytics.repository.RefreshTokenRepository;
import com.contentanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Security revocations that must survive the caller's transaction rolling back.
 *
 * A replay is reported by throwing {@code InvalidTokenException}, which marks
 * the surrounding {@code @Transactional} method rollback-only. Doing the family
 * revocation and token-version bump in that same transaction would silently
 * undo the only useful part of the response. These methods therefore run in
 * their own, independently committed transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyAndBumpVersion(UUID userId, UUID familyId) {
        LocalDateTime now = LocalDateTime.now();
        if (familyId != null) {
            refreshTokenRepository.revokeFamily(familyId, now, RefreshTokenStatus.REVOKED);
        }
        bumpVersion(userId);
        log.warn("Refresh-token family {} revoked for user {} after reuse detection", familyId, userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllAndBumpVersion(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now(), RefreshTokenStatus.REVOKED);
        bumpVersion(userId);
        log.warn("All refresh tokens revoked for user {} after token-state failure", userId);
    }

    private void bumpVersion(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
            user.setTokenVersion(current + 1);
            userRepository.save(user);
        });
    }
}
