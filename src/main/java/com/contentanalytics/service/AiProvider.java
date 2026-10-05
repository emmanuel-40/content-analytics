package com.contentanalytics.service;

/**
 * Strategy interface for AI analysis backends.
 * <p>
 * Implemented by {@link GeminiService} and {@link AnthropicService}. The active provider is
 * selected at runtime from {@code app.ai.provider}, so switching vendors is a one-line
 * configuration change rather than a code change.
 */
public interface AiProvider {

    /**
     * @return the provider's stable identifier, e.g. {@code "gemini"}.
     */
    String getName();

    /**
     * @return true when the provider is enabled and has the credentials it needs.
     */
    boolean isAvailable();

    /**
     * Analyse document text.
     *
     * @return the analysis, or {@code null} when the provider cannot serve the request
     */
    DocumentAnalysisResult analyzeDocument(String documentText);

}