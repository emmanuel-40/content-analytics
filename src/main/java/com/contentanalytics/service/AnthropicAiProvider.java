package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anthropic Claude implementation of {@link AiProvider}.
 * <p>
 * Kept as an alternative backend. Claude has no free tier, so it is only selected when
 * {@code app.ai.provider=anthropic} and an API key is supplied.
 */
@Service
@Slf4j
public class AnthropicAiProvider implements AiProvider {

    @Value("${app.ai.provider:gemini}")
    private String configuredProvider;

    @Value("${app.anthropic.api-key:}")
    private String apiKey;

    @Value("${app.anthropic.api-url:https://api.anthropic.com/v1}")
    private String apiUrl;

    @Value("${app.anthropic.model:claude-3-5-sonnet-20241022}")
    private String model;

    @Value("${app.anthropic.max-tokens:2048}")
    private int maxTokens;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "anthropic";
    }

    @Override
    public boolean isAvailable() {
        return "anthropic".equalsIgnoreCase(configuredProvider)
                && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public DocumentAnalysisResult analyzeDocument(String documentText) {
        if (!isAvailable()) {
            log.info("Anthropic provider not available (provider={}, keyPresent={})",
                    configuredProvider, apiKey != null && !apiKey.isBlank());
            return null;
        }

        try {
            String prompt = buildAnalysisPrompt(documentText);
            String response = callClaudeAPI(prompt);
            return parseAnalysisResponse(response);
        } catch (Exception e) {
            log.error("Error analyzing document with Claude API", e);
            return null;
        }
    }

    private String buildAnalysisPrompt(String documentText) {

        String truncatedText = documentText;
        if (documentText.length() > 10000) {
            truncatedText = documentText.substring(0, 10000) + "...[truncated]";
        }

        return """
            You are a document analysis expert. Analyze the following document and provide:
            
            1. A concise summary (2-3 paragraphs)
            2. Key topics (comma-separated list)
            3. Sentiment analysis (POSITIVE, NEUTRAL, or NEGATIVE)
            4. Confidence score (0.0-1.0)
            
            Document:
            ---
            """ + truncatedText + """
            ---
            
            Provide your response in the following JSON format:
            {
              "summary": "...",
              "keyTopics": "topic1, topic2, topic3",
              "sentiment": "NEUTRAL",
              "confidenceScore": 0.85
            }
            
            Respond ONLY with valid JSON, no additional text.
            """;
    }

    private String callClaudeAPI(String prompt) throws IOException {

        log.debug("Calling Claude API with model: {}", model);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("messages", List.of(
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        ));

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.parse("application/json") // FIX 2 (new OkHttp compatible)
        );

        Request request = new Request.Builder()
                .url(apiUrl + "/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Claude API error: {} - {}", response.code(), errorBody);
                throw new RuntimeException("Claude API error: " + response.code());
            }

            String responseBody = response.body().string();

            return extractTextFromResponse(responseBody);
        }
    }

    private String extractTextFromResponse(String responseJson) throws IOException {

        Map<String, Object> response = objectMapper.readValue(responseJson, Map.class);

        List<Map<String, Object>> content =
                (List<Map<String, Object>>) response.get("content");

        if (content == null || content.isEmpty()) {
            throw new RuntimeException("No content in Claude API response");
        }

        return (String) content.get(0).get("text");
    }

    private DocumentAnalysisResult parseAnalysisResponse(String response) {

        try {
            String jsonContent = response;

            // FIX 3: safer JSON extraction
            Pattern pattern = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(response);

            if (matcher.find()) {
                jsonContent = matcher.group();
            }

            Map<String, Object> parsed = objectMapper.readValue(jsonContent, Map.class);

            Object score = parsed.get("confidenceScore");

            float confidence = score != null
                    ? ((Number) score).floatValue()
                    : 0f;

            return DocumentAnalysisResult.builder()
                    .summary((String) parsed.get("summary"))
                    .keyTopics((String) parsed.get("keyTopics"))
                    .sentiment((String) parsed.get("sentiment"))
                    .confidenceScore(confidence)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing Claude API response", e);

            return DocumentAnalysisResult.builder()
                    .summary("Analysis could not be completed")
                    .keyTopics("N/A")
                    .sentiment("NEUTRAL")
                    .confidenceScore(0f)
                    .build();
        }
    }
}