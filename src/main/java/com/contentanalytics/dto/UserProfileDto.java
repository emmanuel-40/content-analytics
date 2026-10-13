package com.contentanalytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileDto {

    // Basic info
    private UUID userId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;

    // Profile info
    private String bio;
    private String phoneNumber;
    private String location;
    private String timezone;
    private String website;

    // Avatar URLs
    private String avatarUrl; // Medium size (400x400)
    private String thumbnailUrl; // Small size (150x150)

    // Preferences
    private Boolean emailNotifications;
    private Boolean twoFactorEnabled;
    private Boolean publicProfile;

    // Statistics
    private Integer totalDocumentsProcessed;
    private Integer totalAnalysisRequests;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime lastProfileUpdateAt;

}
