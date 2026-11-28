package com.contentanalytics.service;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic extractive summary used when an AI provider is unavailable or returns no
 * usable result. It keeps completed documents useful without pretending that AI analysis ran.
 */
final class BasicAnalysisSummary {

    private static final int MAX_SUMMARY_CHARS = 600;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    private BasicAnalysisSummary() {
    }

    static String fromExtractedText(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return "No readable text was found in this document.";
        }

        String normalized = extractedText.replace("\uFEFF", "").replaceAll("\\s+", " ").trim();
        String summary = Arrays.stream(SENTENCE_BOUNDARY.split(normalized))
                .limit(3)
                .collect(Collectors.joining(" "))
                .trim();

        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
            int lastSpace = summary.lastIndexOf(' ');
            if (lastSpace > MAX_SUMMARY_CHARS / 2) {
                summary = summary.substring(0, lastSpace);
            }
            summary = summary + "...";
        }

        return "Extractive summary from document text: " + summary;
    }
}
