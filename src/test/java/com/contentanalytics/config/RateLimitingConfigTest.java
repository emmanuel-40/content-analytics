package com.contentanalytics.config;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitingConfig")
class RateLimitingConfigTest {

    private RateLimitingConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimitingConfig();
        ReflectionTestUtils.setField(config, "maxTrackedClients", 100);
    }

    @Test
    @DisplayName("login bucket allows 5 attempts then throttles")
    void loginBucketIsThrottledAfterFiveAttempts() {
        Bucket bucket = config.getLoginBucket("10.0.0.1");

        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryConsumeAndReturnRemaining(1).isConsumed())
                    .as("attempt %d should be allowed", i + 1)
                    .isTrue();
        }

        assertThat(bucket.tryConsumeAndReturnRemaining(1).isConsumed()).isFalse();
    }

    @Test
    @DisplayName("register bucket allows 4 attempts then throttles")
    void registerBucketIsThrottledAfterFourAttempts() {
        Bucket bucket = config.getRegisterBucket("10.0.0.2");

        for (int i = 0; i < 4; i++) {
            assertThat(bucket.tryConsumeAndReturnRemaining(1).isConsumed()).isTrue();
        }

        assertThat(bucket.tryConsumeAndReturnRemaining(1).isConsumed()).isFalse();
    }

    @Test
    @DisplayName("the same client IP always receives the same bucket instance")
    void sameClientGetsSameBucket() {
        Bucket first = config.getLoginBucket("10.0.0.3");
        Bucket second = config.getLoginBucket("10.0.0.3");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("login and register buckets are tracked separately for the same IP")
    void loginAndRegisterBucketsAreIndependent() {
        Bucket login = config.getLoginBucket("10.0.0.4");
        Bucket register = config.getRegisterBucket("10.0.0.4");

        assertThat(login).isNotSameAs(register);
    }

    @Test
    @DisplayName("tracked client cache stays bounded under many distinct IPs")
    void trackedClientCacheIsBounded() {
        ReflectionTestUtils.setField(config, "maxTrackedClients", 50);

        for (int i = 0; i < 500; i++) {
            config.getLoginBucket("10.1." + (i / 256) + "." + (i % 256));
        }

        @SuppressWarnings("unchecked")
        var buckets = (java.util.Map<String, Bucket>) ReflectionTestUtils.getField(config, "cacheBuckets");

        assertThat(buckets).hasSizeLessThanOrEqualTo(50);
    }

}