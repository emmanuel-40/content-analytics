package com.contentanalytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalyticsUpdateMessage {

    private UUID userId;
    private Long totalDocuments;
    private Long completedDocuments;
    private Long processingDocuments;
    private Long failedDocuments;
    private Long totalWords;
    private Double averageProcessingTimeMs;
    private LocalDateTime lastUpdateAt;

}
