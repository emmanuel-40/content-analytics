package com.contentanalytics.repository;

import com.contentanalytics.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /** Webhook lookup: the reference Paystack echoes back. */
    Optional<PaymentTransaction> findByReference(String reference);

    /**
     * Lock the payment row so concurrent webhook + browser-verify cannot both
     * flip PENDING → SUCCESS and grant credits twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PaymentTransaction t WHERE t.reference = :reference")
    Optional<PaymentTransaction> findByReferenceForUpdate(@Param("reference") String reference);

    List<PaymentTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
