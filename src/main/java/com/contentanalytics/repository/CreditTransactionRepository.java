package com.contentanalytics.repository;

import com.contentanalytics.entity.CreditTransaction;
import com.contentanalytics.entity.CreditTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    /**
     * Transaction history for a user, newest first.
     */
    Page<CreditTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<CreditTransaction> findByUserIdAndType(UUID userId, CreditTransactionType type);

    long countByUserIdAndType(UUID userId, CreditTransactionType type);

    /**
     * Total credits purchased by a user (lifetime), used for reporting.
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CreditTransaction c "
            + "WHERE c.user.id = :userId AND c.type = 'PURCHASE'")
    long sumPurchasedCredits(@Param("userId") UUID userId);

    /**
     * Total credits consumed by AI analysis (lifetime).
     */
    @Query("SELECT COALESCE(SUM(-c.amount), 0) FROM CreditTransaction c "
            + "WHERE c.user.id = :userId AND c.type = 'AI_ANALYSIS'")
    long sumCreditsConsumed(@Param("userId") UUID userId);

}