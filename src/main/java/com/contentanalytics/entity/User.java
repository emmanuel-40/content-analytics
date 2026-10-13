package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_username", columnList = "username", unique = true),
        @Index(name = "idx_users_email", columnList = "email", unique = true),
        @Index(name = "idx_users_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // Hashed with bcrypt. NULL for accounts created by an identity provider
    // (Google sign-up) that never set a password - see V11 migration.
    @Column
    private String password;

    /**
     * Where this account's credentials come from: {@link AuthProvider#LOCAL} for a
     * password account, {@link AuthProvider#GOOGLE} once the row has been linked to
     * a Google identity.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /**
     * Google's immutable {@code sub} claim. Unique among Google accounts, {@code null}
     * for accounts that have never signed in through an identity provider.
     */
    @Column(length = 64)
    private String providerSubject;

    @Column(length = 255)
    private String firstName;

    @Column(length = 255)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role; // ADMIN, USER

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status; // ACTIVE, INACTIVE, BANNED

    @Column(nullable = false)
    private Integer tokenVersion; // For token rotation/invalidation

    @Column(nullable = false)
    private Boolean emailVerified;

    @Column
    private LocalDateTime emailVerifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime lastLoginAt;

    @Column
    private LocalDateTime lastPasswordResetAt;

    @Column
    private LocalDateTime lastPasswordChangeAt;

}
