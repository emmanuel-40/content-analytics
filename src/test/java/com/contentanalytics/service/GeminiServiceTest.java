package com.contentanalytics.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Gemini temporary failure classification and model fallback")
class GeminiServiceTest {

    @Test
    @DisplayName("timeouts are temporary so the fallback model is tried")
    void timeoutsAreTemporary() {
        GeminiService service = new GeminiService(60);
        assertThat(invoke(service, new SocketTimeoutException("timeout"))).isTrue();
        assertThat(invoke(service, new TimeoutException("deadline exceeded"))).isTrue();
        assertThat(invoke(service, new IllegalStateException("Gemini API error: 503"))).isTrue();
    }

    @Test
    @DisplayName("authentication and parsing failures do not trigger another model")
    void permanentFailuresAreNotTemporary() {
        GeminiService service = new GeminiService(60);
        assertThat(invoke(service, new IllegalStateException("Gemini API error: 400"))).isFalse();
        assertThat(invoke(service, new IllegalStateException("Gemini response could not be parsed"))).isFalse();
    }

    @Test
    @DisplayName("primary timeout falls through to the free fallback model")
    void primaryTimeoutUsesFallbackModel() {
        GeminiService service = new GeminiService(60);
        ReflectionTestUtils.setField(service, "configuredProvider", "gemini");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "apiUrl", "http://localhost");
        ReflectionTestUtils.setField(service, "model", "gemini-3.8-flash");
        ReflectionTestUtils.setField(service, "fallbackModels", "gemini-3.5-flash-lite");
        ReflectionTestUtils.setField(service, "maxTokens", 2048);

        AtomicInteger calls = new AtomicInteger();
        List<String> urls = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    urls.add(chain.request().url().toString());
                    if (calls.getAndIncrement() == 0) {
                        throw new SocketTimeoutException("timeout");
                    }
                    String apiJson = """
                            {
                              "candidates": [{
                                "content": {
                                  "parts": [{
                                    "text": "{\\\"summary\\\":\\\"Fallback summary\\\",\\\"keyTopics\\\":\\\"solar, wind\\\",\\\"sentiment\\\":\\\"POSITIVE\\\",\\\"confidenceScore\\\":0.87}"
                                  }]
                                }
                              }]
                            }
                            """;
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(apiJson, MediaType.get("application/json")))
                            .build();
                })
                .build();
        ReflectionTestUtils.setField(service, "httpClient", client);

        DocumentAnalysisResult result = service.analyzeDocument("Renewable energy is expanding quickly.");

        assertThat(result).isNotNull();
        assertThat(result.getSummary()).isEqualTo("Fallback summary");
        assertThat(result.getKeyTopics()).isEqualTo("solar, wind");
        assertThat(result.getSentiment()).isEqualTo("POSITIVE");
        assertThat(result.getConfidenceScore()).isEqualTo(0.87f);
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).contains("gemini-3.8-flash");
        assertThat(urls.get(1)).contains("gemini-3.5-flash-lite");
    }

    private boolean invoke(GeminiService service, Exception error) {
        Boolean result = ReflectionTestUtils.invokeMethod(service, "isTemporaryModelFailure", error);
        return Boolean.TRUE.equals(result);
    }
}
