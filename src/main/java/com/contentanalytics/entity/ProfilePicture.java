package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "profile_pictures", indexes = {
        @Index(name = "idx_profile_pictures_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_profile_pictures_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfilePicture {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Original file info
    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false)
    private Long originalFileSize; // in bytes

    @Column(nullable = false)
    private String mimeType; // image/jpeg, image/png, etc

    // Storage info
    @Column(nullable = false)
    private String storageKey; // S3 or local path

    // Image URLs (CDN ready)
    @Column(nullable = false)
    private String thumbnailUrl; // 150x150 - for avatars

    @Column(nullable = false)
    private String mediumUrl; // 400x400 - for profile page

    @Column(nullable = false)
    private String fullUrl; // 800x800 - for large display

    // Image dimensions
    @Column
    private Integer width;

    @Column
    private Integer height;

    // Metadata
    @Column
    private String uploadedFrom; // "web", "mobile", "api"

    @Column
    private String ipAddress; // For audit trail

    @Column(nullable = false)
    private Boolean isActive; // Can disable without deleting

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
