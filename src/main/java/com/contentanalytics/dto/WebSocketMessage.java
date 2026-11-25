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
public class WebSocketMessage {

    // Canonical message-type values (also exposed as the public contract).
    public static final String PROGRESS = "PROGRESS";
    public static final String PROCESSING_COMPLETED = "PROCESSING_COMPLETED";
    public static final String PROCESSING_FAILED = "PROCESSING_FAILED";
    public static final String STATUS = "STATUS";

    // Backwards-compatible aliases used internally.
    public static final String TYPE_PROGRESS = PROGRESS;
    public static final String TYPE_PROCESSING_COMPLETED = PROCESSING_COMPLETED;
    public static final String TYPE_PROCESSING_FAILED = PROCESSING_FAILED;
    public static final String TYPE_STATUS = STATUS;

    // Message metadata
    private UUID messageId;
    private String messageType; // PROGRESS, PROCESSING_COMPLETED, PROCESSING_FAILED, STATUS
    private LocalDateTime timestamp;

    // Document info
    private UUID documentId;
    private String fileName;

    /** Spring user-destination id (the JWT user id rendered as a string). */
    private String userId;

    // Progress data
    private Float progress; // 0-100%
    private String status; // UPLOADED, PROCESSING, COMPLETED, FAILED

    // Analysis results (on completion)
    private String summary;
    private String keyTopics;
    private String sentiment;
    private Float confidenceScore;
    private Integer wordCount;

    // Error handling
    private String errorMessage;
    private String errorCode;

    // Additional metadata
    private Long processingTimeMs;
    private String processingStage; // "extracting_text", "analyzing", "saving_results"

}
