package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_username", columnList = "username", unique = true),
        @Index(name = "idx_email", columnList = "email", unique = true),
        @Index(name = "idx_created_at", columnList = "created_at")
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

    @Column(nullable = false)
    private String password; // Hashed with bcrypt

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
