package com.contentanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiAnalysisService provider selection and fallback")
class AiAnalysisServiceTest {

    /** Test double that reports a configurable availability. */
    private static class StubProvider implements AiProvider {
        private final String name;
        private final boolean available;
        private final boolean shouldThrow;

        StubProvider(String name, boolean available, boolean shouldThrow) {
            this.name = name;
            this.available = available;
            this.shouldThrow = shouldThrow;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public DocumentAnalysisResult analyzeDocument(String text) {
            if (shouldThrow) {
                throw new RuntimeException("simulated provider outage");
            }
            return DocumentAnalysisResult.builder().summary("ok").build();
        }
    }

    private AiAnalysisService service;

    @BeforeEach
    void setUp() {
        // Built in the test body below; each test configures its own providers.
        service = new AiAnalysisService(List.of());
    }

    private AiAnalysisService withProvider(String configured, AiProvider... providers) {
        AiAnalysisService svc = new AiAnalysisService(List.of(providers));
        ReflectionTestUtils.setField(svc, "configuredProvider", configured);
        return svc;
    }

    @Test
    @DisplayName("uses the configured provider when it is available")
    void usesConfiguredProvider() {
        AiAnalysisService svc = withProvider("gemini",
                new StubProvider("gemini", true, false),
                new StubProvider("anthropic", true, false));

        assertThat(svc.isEnabled()).isTrue();
        assertThat(svc.getActiveProvider()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("an explicitly configured provider with no key disables AI instead of silently switching vendor")
    void doesNotSilentlySwitchVendor() {
        AiAnalysisService svc = withProvider("gemini",
                new StubProvider("gemini", false, false),
                new StubProvider("anthropic", true, false));

        assertThat(svc.isEnabled())
                .as("operator configured Gemini, so data must not be sent to another vendor")
                .isFalse();
        assertThat(svc.getActiveProvider()).isEqualTo("none");
    }

    @Test
    @DisplayName("with no provider configured, any available provider is used")
    void usesAnyProviderWhenUnconfigured() {
        AiAnalysisService svc = withProvider(null,
                new StubProvider("gemini", false, false),
                new StubProvider("anthropic", true, false));

        assertThat(svc.getActiveProvider()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("reports disabled when no provider has credentials")
    void disabledWhenNoProviderAvailable() {
        AiAnalysisService svc = withProvider("gemini",
                new StubProvider("gemini", false, false),
                new StubProvider("anthropic", false, false));

        assertThat(svc.isEnabled()).isFalse();
        assertThat(svc.getActiveProvider()).isEqualTo("none");
        assertThat(svc.analyze("some document text")).isNull();
    }

    @Test
    @DisplayName("provider=none disables AI entirely")
    void noneDisablesAi() {
        AiAnalysisService svc = withProvider("none", new StubProvider("gemini", true, false));

        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a provider exception degrades to null instead of propagating")
    void providerFailureDegradesGracefully() {
        AiAnalysisService svc = withProvider("gemini", new StubProvider("gemini", true, true));

        DocumentAnalysisResult result = svc.analyze("some document text");

        assertThat(result).as("caller must be able to fall back to basic analysis").isNull();
    }

    @Test
    @DisplayName("blank document text short-circuits without calling the provider")
    void blankTextIsRejected() {
        AiAnalysisService svc = withProvider("gemini", new StubProvider("gemini", true, false));

        assertThat(svc.analyze("   ")).isNull();
        assertThat(svc.analyze(null)).isNull();
    }

    @Test
    @DisplayName("successful analysis is returned to the caller")
    void successfulAnalysisIsReturned() {
        AiAnalysisService svc = withProvider("gemini", new StubProvider("gemini", true, false));

        assertThat(svc.analyze("real text")).isNotNull();
    }

}