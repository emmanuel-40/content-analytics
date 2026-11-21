package com.contentanalytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAsync
@EnableCaching
@EnableJpaAuditing
@EnableScheduling
@EnableMethodSecurity  // Enables @PreAuthorize on controller/service methods
@EnableAspectJAutoProxy // Enables @Aspect classes (IdempotencyAspect, RateLimitingAspect)
public class ContentAnalyticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ContentAnalyticsApplication.class, args);
	}

}