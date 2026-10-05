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
public class DocumentStatusDto {

    private UUID documentId;
    private String fileName;
    private String status; // UPLOADED, PROCESSING, COMPLETED, FAILED
    private Float processingProgress; // 0-100%
    private String processingError;
    private Boolean isCompleted;
    private Boolean isFailed;
    private LocalDateTime processingCompletedAt;

}