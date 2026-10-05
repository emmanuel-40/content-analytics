package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A user's current subscription standing and credit balance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionDto {

    private String tier;
    private String status;
    private Integer monthlyCreditAllowance;
    private Integer creditsUsedThisPeriod;
    private Integer purchasedCredits;
    private Integer availableCredits;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Boolean autoRenew;

}