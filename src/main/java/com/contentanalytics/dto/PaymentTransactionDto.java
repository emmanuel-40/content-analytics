package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One checkout attempt, as shown in the billing history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionDto {

    private UUID id;
    private String reference;
    private String provider;
    private int amountKobo;
    private int credits;
    private String status;
    private String checkoutUrl;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
