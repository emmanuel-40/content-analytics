package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single entry from the credit ledger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditTransactionDto {

    private UUID id;
    private Integer amount;
    private Integer balanceAfter;
    private String type;
    private String description;
    private String referenceId;
    private LocalDateTime createdAt;

}