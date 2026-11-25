package com.contentanalytics.service;

import com.contentanalytics.entity.*;
import com.contentanalytics.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 moved JPA test slices out of the old
// org.springframework.boot.test.autoconfigure.orm.jpa package.
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the conditional UPDATEs on refresh_tokens against the H2 test
 * schema. No Spring web/security context is started - just JPA.
 */
@DataJpaTest
@Import(RefreshTokenClaimTest.CacheTestConfig.class)
@ActiveProfiles("test")
@DisplayName("Refresh token claim (repository)")
class RefreshTokenClaimTest {

    /** @EnableCaching on the app class applies to slices too; provide a local manager. */
    @TestConfiguration
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestEntityManager em;

    private User user;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = em.persist(User.builder()
                .username("rt-" + suffix)
                .email("rt-" + suffix + "@example.com")
                .password("hash")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        em.flush();
    }

    @Test
    @DisplayName("the first claim wins, records the replacement and every later claim fails")
    void claimIsSingleUse() {
        String hash = "a".repeat(64);
        UUID replacementId = UUID.randomUUID();
        RefreshToken row = em.persist(row(hash, UUID.randomUUID(), LocalDateTime.now().plusDays(7)));
        em.flush();

        assertThat(refreshTokenRepository.claimToken(
                hash, LocalDateTime.now(), replacementId, RefreshTokenStatus.ACTIVE, RefreshTokenStatus.USED))
                .isEqualTo(1);

        em.flush();
        em.clear();
        RefreshToken reloaded = refreshTokenRepository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefreshTokenStatus.USED);
        assertThat(reloaded.getUsed()).isTrue();
        assertThat(reloaded.getRevoked()).isFalse();
        assertThat(reloaded.getReplacedBy()).isEqualTo(replacementId);

        assertThat(refreshTokenRepository.claimToken(
                hash, LocalDateTime.now(), UUID.randomUUID(), RefreshTokenStatus.ACTIVE, RefreshTokenStatus.USED))
                .isZero();
    }

    @Test
    @DisplayName("an expired token can never be claimed")
    void expiredTokenIsNotClaimable() {
        String hash = "b".repeat(64);
        em.persist(row(hash, UUID.randomUUID(), LocalDateTime.now().minusMinutes(1)));
        em.flush();

        assertThat(refreshTokenRepository.claimToken(
                hash, LocalDateTime.now(), UUID.randomUUID(), RefreshTokenStatus.ACTIVE, RefreshTokenStatus.USED))
                .isZero();
    }

    @Test
    @DisplayName("revoking a family leaves unrelated sessions untouched")
    void familyRevocationIsScoped() {
        UUID family = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();
        em.persist(row("c".repeat(64), family, LocalDateTime.now().plusDays(7)));
        em.persist(row("d".repeat(64), family, LocalDateTime.now().plusDays(7)));
        em.persist(row("e".repeat(64), otherFamily, LocalDateTime.now().plusDays(7)));
        em.flush();

        assertThat(refreshTokenRepository.revokeFamily(family, LocalDateTime.now(), RefreshTokenStatus.REVOKED))
                .isEqualTo(2);

        em.flush();
        em.clear();
        assertThat(refreshTokenRepository.findByTokenHash("c".repeat(64)).orElseThrow().getStatus())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(refreshTokenRepository.findByTokenHash("d".repeat(64)).orElseThrow().getStatus())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(refreshTokenRepository.findByTokenHash("e".repeat(64)).orElseThrow().getStatus())
                .isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    @DisplayName("token hashes are unique at the database level")
    void tokenHashIsUnique() {
        em.persist(row("f".repeat(64), UUID.randomUUID(), LocalDateTime.now().plusDays(7)));
        em.flush();

        em.persist(row("f".repeat(64), UUID.randomUUID(), LocalDateTime.now().plusDays(7)));
        assertThatThrownBy(() -> em.flush()).isInstanceOf(Exception.class);
    }

    private RefreshToken row(String hash, UUID familyId, LocalDateTime expiry) {
        return RefreshToken.builder()
                .user(user)
                .tokenHash(hash)
                .familyId(familyId)
                .status(RefreshTokenStatus.ACTIVE)
                .used(false)
                .revoked(false)
                .expiresAt(expiry)
                .build();
    }
}
