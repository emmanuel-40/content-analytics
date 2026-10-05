package com.contentanalytics.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured output produced by an {@link AiProvider}.
 * <p>
 * Lives in the service package because it is an internal transport type between the AI layer
 * and the document pipeline, not part of the public API surface.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    private String summary;
    private String keyTopics;
    private String sentiment;
    private Float confidenceScore;

}