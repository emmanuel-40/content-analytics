package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_profiles", indexes = {
        @Index(name = "idx_user_profiles_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_user_profiles_updated_at", columnList = "updated_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Profile information
    @Column(length = 500)
    private String bio; // About user

    @Column(length = 50)
    private String phoneNumber;

    @Column(length = 100)
    private String location; // City, Country

    @Column(length = 50)
    private String timezone; // UTC, PST, etc

    @Column(length = 255)
    private String website; // Personal website

    // Preferences
    @Column(nullable = false)
    private Boolean emailNotifications; // Receive emails?

    @Column(nullable = false)
    private Boolean twoFactorEnabled; // 2FA enabled?

    @Column(nullable = false)
    private Boolean publicProfile; // Profile visible to others?

    @Column(nullable = false, name = "show_email_publicly")
    private Boolean showEmailPublically; // Hide email from others?

    // Statistics
    @Column(nullable = false)
    private Integer totalDocumentsProcessed; // Aggregate stat

    @Column(nullable = false)
    private Integer totalAnalysisRequests;

    @Column
    private LocalDateTime lastProfileUpdateAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
