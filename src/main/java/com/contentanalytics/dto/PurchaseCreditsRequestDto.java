package com.contentanalytics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to purchase additional credits (pay-as-you-go).
 * <p>
 * The payment provider reference is optional so the flow can be exercised before a payment
 * gateway is wired in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseCreditsRequestDto {

    @NotNull(message = "Credit amount is required")
    @Min(value = 1, message = "Must purchase at least 1 credit")
    private Integer credits;

    /**
     * Idempotency key from the client, preventing duplicate purchases on retry.
     */
    private String referenceId;

}