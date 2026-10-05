package com.contentanalytics.repository;

import com.contentanalytics.entity.Subscription;
import com.contentanalytics.entity.SubscriptionStatus;
import com.contentanalytics.entity.SubscriptionTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserId(UUID userId);

    List<Subscription> findByTier(SubscriptionTier tier);

    List<Subscription> findByStatus(SubscriptionStatus status);

    long countByTier(SubscriptionTier tier);

    /**
     * Load a subscription for update.
     * <p>
     * The pessimistic write lock serialises concurrent credit deductions for the same user.
     * Without it, two simultaneous AI requests could both read "1 credit remaining" and both
     * succeed, driving the balance negative.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.user.id = :userId")
    Optional<Subscription> findByUserIdForUpdate(@Param("userId") UUID userId);

    /**
     * Subscriptions whose credit period has elapsed and need rolling over.
     */
    @Query("SELECT s FROM Subscription s WHERE s.periodEnd < :now AND s.status = 'ACTIVE'")
    List<Subscription> findExpiredPeriods(@Param("now") LocalDateTime now);

}