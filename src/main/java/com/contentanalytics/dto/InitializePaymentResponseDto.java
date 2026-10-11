package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the SPA needs to send the user to the provider checkout page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitializePaymentResponseDto {

    private String reference;
    private String checkoutUrl;
    private String accessCode;
    private int amountKobo;
    private int credits;
    private String status;
    private String publicKey;
}
