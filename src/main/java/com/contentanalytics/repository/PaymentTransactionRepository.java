package com.contentanalytics.repository;

import com.contentanalytics.entity.PaymentStatus;
import com.contentanalytics.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /** Webhook lookup: the reference Paystack echoes back. */
    Optional<PaymentTransaction> findByReference(String reference);

    List<PaymentTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
