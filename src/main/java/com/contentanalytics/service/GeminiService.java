package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini implementation of {@link AiProvider}.
 * <p>
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

    @Value("${app.gemini.model:gemini-2.5-flash}")
    private String model;

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

        try {
            return parseResponse(callGeminiApi(buildPrompt(documentText)));
        } catch (Exception e) {
            log.error("Gemini analysis failed", e);
            return null;
        }
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

    private String callGeminiApi(String prompt) throws java.io.IOException {
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
                .url(apiUrl + "/models/" + model + ":generateContent?key=" + apiKey)
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
    @SuppressWarnings("unchecked")
    private String extractText(String responseJson) throws java.io.IOException {
        Map<String, Object> parsed = objectMapper.readValue(responseJson, Map.class);

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) parsed.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("No candidates in Gemini response");
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            throw new IllegalStateException("No content in Gemini response");
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new IllegalStateException("No parts in Gemini response");
        }

        return (String) parts.get(0).get("text");
    }

    @SuppressWarnings("unchecked")
    private DocumentAnalysisResult parseResponse(String response) {
        // Models often wrap JSON in prose or markdown fences; extract the first {...} block.
        String jsonContent = response;
        Matcher matcher = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(response);
        if (matcher.find()) {
            jsonContent = matcher.group();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonContent, Map.class);

            Object score = parsed.get("confidenceScore");
            float confidence = score instanceof Number number ? number.floatValue() : 0f;

            return DocumentAnalysisResult.builder()
                    .summary((String) parsed.get("summary"))
                    .keyTopics((String) parsed.get("keyTopics"))
                    .sentiment(normalizeSentiment((String) parsed.get("sentiment")))
                    .confidenceScore(confidence)
                    .build();

        } catch (Exception e) {
            log.error("Could not parse Gemini response as JSON: {}", e.getMessage());
            return null;
        }
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