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
public class DocumentResponseDto {

    private UUID id;
    private UUID userId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private Integer pageCount;
    private String description;
    private String status; // UPLOADED, PROCESSING, COMPLETED, FAILED
    private Float processingProgress; // 0-100%
    private String processingError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime processingCompletedAt;

    // Analysis results

    private Integer wordCount;
    private Integer characterCount;
    private String summary;
    private String keyTopics; // JSON array
    private String sentiment; // POSITIVE, NEUTRAL, NEGATIVE
    private Float confidenceScore;

}
