package com.contentanalytics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Start a real-money checkout.
 * <p>
 * Only fixed packs are accepted ({@code 10}, {@code 50}, {@code 120}) - the
 * price list lives server-side in {@code PaymentService.CREDIT_PACKS}, so a
 * client can never invent its own price.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitializePaymentRequestDto {

    @NotNull(message = "Credit pack is required")
    @Min(value = 1, message = "Must buy at least 1 credit")
    private Integer credits;
}
