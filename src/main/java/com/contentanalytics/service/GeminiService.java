package com.contentanalytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini implementation of {@link AiProvider}.
 *
 * Uses the free tier of the Gemini API. If no API key is configured the provider reports
 * itself unavailable and {@link AiAnalysisService} degrades gracefully to basic analysis
 * rather than failing the document.
 */
@Service
@Slf4j
public class GeminiService implements AiProvider {

    @Value("${app.ai.provider:gemini}")
    private String configuredProvider;

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.api-url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${app.gemini.model:gemini-3.8-flash}")
    private String model;

    /**
     * Free-tier fallbacks for temporary model unavailability (for example a 503 during
     * high demand). The primary model is always tried first.
     */
    @Value("${app.gemini.fallback-models:gemini-3.5-flash-lite}")
    private String fallbackModels;

    @Value("${app.gemini.max-tokens:2048}")
    private int maxTokens;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiService(
            @Value("${app.gemini.timeout-seconds:60}") int timeoutSeconds) {
        this.httpClient = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        return "gemini".equalsIgnoreCase(configuredProvider)
                && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public DocumentAnalysisResult analyzeDocument(String documentText) {
        if (!isAvailable()) {
            log.info("Gemini provider unavailable (provider={}, keyPresent={})",
                    configuredProvider, apiKey != null && !apiKey.isBlank());
            return null;
        }

        String prompt = buildPrompt(documentText);
        Exception lastFailure = null;

        for (String candidate : candidateModels()) {
            try {
                DocumentAnalysisResult result = parseResponse(callGeminiApi(prompt, candidate));
                if (result != null) {
                    return result;
                }
                lastFailure = new IllegalStateException("Gemini response could not be parsed");
            } catch (Exception e) {
                lastFailure = e;
                if (isTemporaryModelFailure(e)) {
                    log.warn("Gemini model {} unavailable ({}); trying fallback", candidate, e.getMessage());
                    continue;
                }
                log.error("Gemini analysis failed for model {}", candidate, e);
                return null;
            }
        }

        log.error("Gemini analysis failed for all configured models", lastFailure);
        return null;
    }

    /** Primary model first, then configured fallbacks, with duplicates removed. */
    private List<String> candidateModels() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (model != null && !model.isBlank()) {
            candidates.add(model.trim());
        }
        if (fallbackModels != null && !fallbackModels.isBlank()) {
            Arrays.stream(fallbackModels.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(candidates::add);
        }
        return new ArrayList<>(candidates);
    }

    /**
     * Model removal and capacity spikes should not lose the analysis: fall through to the
     * next free model. Authentication/quota/parsing errors are not fixed by another model.
     */
    private boolean isTemporaryModelFailure(Exception error) {
        if (error instanceof java.net.SocketTimeoutException
                || error instanceof java.io.InterruptedIOException
                || error instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        String message = error.getMessage() == null
                ? ""
                : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("timeout")
                || message.contains("timed out")
                || Stream.of(" 404", " 408", " 429", " 500", " 502", " 503", " 504")
                        .anyMatch(message::contains);
    }

    /**
     * Build the analysis prompt. The document is truncated so a large upload cannot blow
     * through the context window or rack up excessive token cost.
     */
    private String buildPrompt(String documentText) {
        String truncated = documentText.length() > 10000
                ? documentText.substring(0, 10000) + "...[truncated]"
                : documentText;

        return """
                You are a document analysis expert. Analyse the following document and provide:

                1. A concise summary (2-3 paragraphs)
                2. Key topics (comma-separated list)
                3. Sentiment analysis (POSITIVE, NEUTRAL, or NEGATIVE)
                4. Confidence score (between 0.0 and 1.0)

                Document:
                ---
                %s
                ---

                Respond with ONLY valid JSON in this exact shape, no prose, no markdown fences:
                {"summary": "...", "keyTopics": "topic1, topic2, topic3", "sentiment": "NEUTRAL", "confidenceScore": 0.85}
                """.formatted(truncated);
    }

    private String callGeminiApi(String prompt, String candidateModel) throws java.io.IOException {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", maxTokens,
                        "temperature", 0.2
                )
        );

        String jsonBody = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(apiUrl + "/models/" + candidateModel + ":generateContent?key=" + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Gemini API error {}: {}", response.code(), responseBody);
                throw new IllegalStateException("Gemini API error: " + response.code());
            }

            return extractText(responseBody);
        }
    }

    /**
     * Gemini wraps the response in candidates[].content.parts[].text.
     */
    private String extractText(String responseJson) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("No candidates in Gemini response");
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("No parts in Gemini response");
        }

        JsonNode text = parts.get(0).path("text");
        if (text.isMissingNode() || text.isNull() || text.asText().isBlank()) {
            throw new IllegalStateException("No text in Gemini response");
        }
        return text.asText();
    }

    private DocumentAnalysisResult parseResponse(String response) {
        // Models often wrap JSON in prose or markdown fences; extract the first {...} block.
        String jsonContent = response;
        Matcher matcher = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(response);
        if (matcher.find()) {
            jsonContent = matcher.group();
        }

        try {
            JsonNode parsed = objectMapper.readTree(jsonContent);
            JsonNode score = parsed.path("confidenceScore");
            float confidence = score.isNumber() ? score.floatValue() : 0f;

            return DocumentAnalysisResult.builder()
                    .summary(textOrNull(parsed, "summary"))
                    .keyTopics(textOrNull(parsed, "keyTopics"))
                    .sentiment(normalizeSentiment(textOrNull(parsed, "sentiment")))
                    .confidenceScore(confidence)
                    .build();

        } catch (Exception e) {
            log.error("Could not parse Gemini response as JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Returns a text field, or null when it is missing or explicitly null. */
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * Constrain sentiment to the values the database accepts, so a chatty model reply cannot
     * violate the column and fail the surrounding transaction.
     */
    private String normalizeSentiment(String sentiment) {
        if (sentiment == null) {
            return "NEUTRAL";
        }
        String upper = sentiment.trim().toUpperCase();
        return switch (upper) {
            case "POSITIVE", "NEUTRAL", "NEGATIVE" -> upper;
            default -> "NEUTRAL";
        };
    }

}
