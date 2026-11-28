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
public class DocumentListItemDto {

    private UUID id;
    private String fileName;
    private Long fileSize;
    private String status;
    private Float processingProgress;
    private String sentiment;
    private Integer wordCount;
    /** Short analysis preview so the library can show results without opening each document. */
    private String summary;
    private String processingError;
    private LocalDateTime createdAt;
    private LocalDateTime processingCompletedAt;

}
