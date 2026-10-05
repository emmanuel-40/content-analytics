package com.contentanalytics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Selects the configured {@link AiProvider} and degrades gracefully when none is usable.
 * <p>
 * Adding a new vendor means implementing {@link AiProvider} and adding it to the provider
 * list - no changes to callers. When the selected provider has no API key or errors, the
 * caller receives {@code null} and the document still completes with basic analysis.
 */
@Service
@Slf4j
public class AiAnalysisService {

    private final List<AiProvider> providers;

    @Value("${app.ai.provider:none}")
    private String configuredProvider;

    public AiAnalysisService(List<AiProvider> providers) {
        this.providers = providers;
        log.info("Registered AI providers: {}", providers.stream().map(AiProvider::getName).toList());
    }

    /**
     * @return true when the configured provider is enabled and ready.
     */
    public boolean isEnabled() {
        return resolveProvider() != null;
    }

    /**
     * @return the name of the active provider, or {@code "none"} when unavailable.
     */
    public String getActiveProvider() {
        AiProvider provider = resolveProvider();
        return provider != null ? provider.getName() : "none";
    }

    /**
     * Run analysis through the configured provider.
     *
     * @return the analysis, or {@code null} when AI is unavailable or the call failed.
     *         Callers must treat {@code null} as "continue with basic analysis".
     */
    public DocumentAnalysisResult analyze(String documentText) {
        AiProvider provider = resolveProvider();

        if (provider == null) {
            log.info("No AI provider available; skipping AI analysis");
            return null;
        }

        if (documentText == null || documentText.isBlank()) {
            log.warn("Cannot analyse blank document text");
            return null;
        }

        try {
            DocumentAnalysisResult result = provider.analyzeDocument(documentText);
            if (result == null) {
                log.warn("Provider {} returned no analysis; falling back to basic analysis",
                        provider.getName());
            }
            return result;
        } catch (Exception e) {
            // Never let an AI outage fail a document; basic analysis is still valuable.
            log.error("AI analysis via {} failed; continuing with basic analysis", provider.getName(), e);
            return null;
        }
    }

    /**
     * Resolve the configured provider.
     * <p>
     * Deliberately does NOT silently switch to a different vendor: if an operator explicitly
     * configured a provider and it has no credentials, the correct behaviour is to report that
     * AI is unavailable rather than quietly sending document content to another vendor.
     * When no provider is configured at all, any available provider is used.
     */
    private AiProvider resolveProvider() {
        boolean explicitlyConfigured = configuredProvider != null
                && !configuredProvider.isBlank()
                && !"none".equalsIgnoreCase(configuredProvider);

        if (explicitlyConfigured) {
            for (AiProvider provider : providers) {
                if (provider.getName().equalsIgnoreCase(configuredProvider)) {
                    if (provider.isAvailable()) {
                        return provider;
                    }
                    log.warn("AI provider '{}' is configured but unavailable (missing API key?). "
                            + "AI analysis is disabled until a key is supplied.", configuredProvider);
                    return null;
                }
            }

            log.warn("Configured AI provider '{}' is not registered. Known providers: {}",
                    configuredProvider, providers.stream().map(AiProvider::getName).toList());
            return null;
        }

        if (configuredProvider != null && "none".equalsIgnoreCase(configuredProvider)) {
            log.info("AI analysis explicitly disabled (app.ai.provider=none)");
            return null;
        }

        // No provider configured: use whichever is ready.
        return providers.stream()
                .filter(AiProvider::isAvailable)
                .findFirst()
                .orElse(null);
    }

}