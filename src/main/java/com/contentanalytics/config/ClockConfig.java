package com.contentanalytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Single {@link Clock} bean so every time read in the app flows through one source.
 * <p>
 * Injecting {@code Clock} (instead of calling {@code LocalDateTime.now()} directly)
 * is what makes time-dependent logic testable: tests supply {@link Clock#fixed} and
 * get exact, repeatable period boundaries instead of values that race the wall clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
