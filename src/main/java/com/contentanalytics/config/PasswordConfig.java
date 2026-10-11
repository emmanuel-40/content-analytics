package com.contentanalytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing, kept in its own configuration on purpose.
 * <p>
 * The {@code PasswordEncoder} bean is needed by {@code AuthService} (register,
 * login, password reset), which is in turn needed by the OAuth2 login success
 * handler, which is in turn needed by {@code SecurityConfig}. If this bean
 * lived in {@code SecurityConfig}, that chain would become a circular
 * dependency and the application context would refuse to start
 * ("Requested bean is currently in creation"). A standalone configuration
 * breaks the cycle: hashing is available before any security wiring runs.
 */
@Configuration
public class PasswordConfig {

    /**
     * Password encoder with 12 rounds (strong hashing)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
