package com.contentanalytics.service;

import com.contentanalytics.dto.ImageProcessingResult;
import com.contentanalytics.dto.UpdateProfileRequestDto;
import com.contentanalytics.dto.UserProfileDto;
import com.contentanalytics.dto.ChangePasswordRequestDto;
import com.contentanalytics.entity.*;
import com.contentanalytics.exception.UserNotFoundException;
import com.contentanalytics.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProfileService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ProfilePictureRepository profilePictureRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final ImageService imageService;
    private final GravatarService gravatarService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final StorageService storageService;

    @Value("${app.password.min-change-interval-hours:1}")
    private int minPasswordChangeInterval;

    /**
     * How long a generated image URL stays valid.
     * <p>
     * Short enough that a leaked URL stops working, long enough to survive a page render plus
     * a few navigation clicks.
     */
    private static final java.time.Duration IMAGE_URL_TTL = java.time.Duration.ofHours(1);

    /**
     * Resolve a stored image key into something a browser can load.
     * <p>
     * The {@code *_url} columns on {@code profile_pictures} actually hold storage keys, not URLs.
     * Turning them into a real URL here is what allows the bucket to stay private:
     * <ul>
     *   <li>S3 / Supabase - a short-lived presigned URL</li>
     *   <li>local filesystem - the authenticated serving route, so local dev needs no public
     *       directory either</li>
     * </ul>
     *
     * @param storageKey the key to resolve
     * @return a loadable URL, or {@code null} when there is nothing to serve
     */
    public String resolveImageUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }

        String signed = storageService.generatePresignedUrl(storageKey, IMAGE_URL_TTL);
        if (signed != null) {
            return signed;
        }

        // Local storage: fall back to the authenticated serving endpoint.
        return "/api/profile/picture/file?key="
                + java.net.URLEncoder.encode(storageKey, StandardCharsets.UTF_8);
    }

    /**
     * Get user profile (complete with picture)
     */
    public UserProfileDto getUserProfile(UUID userId) {
        log.debug("Fetching profile for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // NOTE: orElseGet (not orElse) -- orElse evaluates its argument eagerly, so using
        // orElse here would INSERT a duplicate user_profiles row on every single read and
        // blow up with a unique-constraint violation as soon as one already existed.
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultProfile(user));

        // Get profile picture
        ProfilePicture profilePicture = profilePictureRepository.findByUserId(userId)
                .filter(ProfilePicture::getIsActive)
                .orElse(null);

        String avatarUrl;
        if (profilePicture != null) {
            // Stored value is a storage key; resolve it to a loadable (signed) URL.
            avatarUrl = resolveImageUrl(profilePicture.getMediumUrl());
        } else {
            // Fallback to Gravatar
            avatarUrl = gravatarService.getGravatarUrl(user.getEmail(), 400);
        }

        return UserProfileDto.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .bio(profile.getBio())
                .phoneNumber(profile.getPhoneNumber())
                .location(profile.getLocation())
                .timezone(profile.getTimezone())
                .website(profile.getWebsite())
                .company(profile.getCompany())
                .jobTitle(profile.getJobTitle())
                .avatarUrl(avatarUrl)
                .thumbnailUrl(profilePicture != null
                        ? resolveImageUrl(profilePicture.getThumbnailUrl())
                        : gravatarService.getGravatarUrl(user.getEmail(), 150))
                .emailNotifications(profile.getEmailNotifications())
                .twoFactorEnabled(profile.getTwoFactorEnabled())
                .publicProfile(profile.getPublicProfile())
                .totalDocumentsProcessed(profile.getTotalDocumentsProcessed())
                .totalAnalysisRequests(profile.getTotalAnalysisRequests())
                .createdAt(user.getCreatedAt())
                .lastProfileUpdateAt(profile.getLastProfileUpdateAt())
                .build();
    }

    /**
     * Update user profile
     */
    public UserProfileDto updateProfile(UUID userId, UpdateProfileRequestDto request) {
        log.info("Updating profile for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Check if profile exists using existsByUserId to avoid Hibernate cache issues
        boolean profileExists = userProfileRepository.existsByUserId(userId);

        UserProfile profile;
        if (profileExists) {
            profile = userProfileRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Profile exists but couldn't be fetched"));
            log.debug("Found existing profile for user: {}", userId);
        } else {
            log.debug("No existing profile found for user: {}, creating default profile", userId);
            profile = UserProfile.builder()
                    .user(user)
                    .emailNotifications(true)
                    .twoFactorEnabled(false)
                    .publicProfile(false)
                    .showEmailPublically(false)
                    .totalDocumentsProcessed(0)
                    .totalAnalysisRequests(0)
                    .build();
            profile = userProfileRepository.saveAndFlush(profile);
            log.debug("Created default profile for user: {}", userId);
        }

        // Update user info
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }

        // Update profile info
        if (request.getBio() != null) {
            profile.setBio(request.getBio());
        }
        if (request.getPhoneNumber() != null) {
            profile.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getLocation() != null) {
            profile.setLocation(request.getLocation());
        }
        if (request.getTimezone() != null) {
            profile.setTimezone(request.getTimezone());
        }
        if (request.getWebsite() != null) {
            profile.setWebsite(request.getWebsite());
        }
        if (request.getCompany() != null) {
            profile.setCompany(request.getCompany());
        }
        if (request.getJobTitle() != null) {
            profile.setJobTitle(request.getJobTitle());
        }
        if (request.getEmailNotifications() != null) {
            profile.setEmailNotifications(request.getEmailNotifications());
        }
        if (request.getPublicProfile() != null) {
            profile.setPublicProfile(request.getPublicProfile());
        }

        profile.setLastProfileUpdateAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        userRepository.save(user);
        userProfileRepository.saveAndFlush(profile);  // ← Change to saveAndFlush

        log.info("Profile updated for user: {}", userId);

// Build and return DTO without re-querying
        ProfilePicture profilePicture = profilePictureRepository.findByUserId(userId)
                .filter(ProfilePicture::getIsActive)
                .orElse(null);

        String avatarUrl = profilePicture != null
                ? resolveImageUrl(profilePicture.getMediumUrl())
                : gravatarService.getGravatarUrl(user.getEmail(), 400);

        return UserProfileDto.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .bio(profile.getBio())
                .phoneNumber(profile.getPhoneNumber())
                .location(profile.getLocation())
                .timezone(profile.getTimezone())
                .website(profile.getWebsite())
                .company(profile.getCompany())
                .jobTitle(profile.getJobTitle())
                .avatarUrl(avatarUrl)
                .thumbnailUrl(profilePicture != null
                        ? resolveImageUrl(profilePicture.getThumbnailUrl())
                        : gravatarService.getGravatarUrl(user.getEmail(), 150))
                .emailNotifications(profile.getEmailNotifications())
                .twoFactorEnabled(profile.getTwoFactorEnabled())
                .publicProfile(profile.getPublicProfile())
                .totalDocumentsProcessed(profile.getTotalDocumentsProcessed())
                .totalAnalysisRequests(profile.getTotalAnalysisRequests())
                .createdAt(user.getCreatedAt())
                .lastProfileUpdateAt(profile.getLastProfileUpdateAt())
                .build();
    }

    /**
     * Upload profile picture
     */
    public UserProfileDto uploadProfilePicture(UUID userId, MultipartFile file, String ipAddress) {
        log.info("Uploading profile picture for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        try {
            // Process image (resize, optimize)
            ImageProcessingResult imageResult = imageService.processProfilePicture(file, userId);

            // profile_pictures.user_id is UNIQUE (V7 migration): exactly one row per user.
            // The previous "deactivate old row, then insert a new one" flow violated that
            // constraint on every re-upload, so the *second* avatar a user ever picked threw a
            // 500. Update the single row in place instead, and clean up the objects it held.
            ProfilePicture picture = profilePictureRepository.findByUserId(userId)
                    .orElseGet(() -> ProfilePicture.builder().user(user).build());

            deleteStoredKeys(
                    picture.getStorageKey(),
                    picture.getThumbnailUrl(),
                    picture.getMediumUrl(),
                    picture.getFullUrl());

            // These columns store storage KEYS, not URLs. They are turned into
            // short-lived signed URLs on read (see resolveImageUrl), which is what
            // lets the storage bucket stay private.
            picture.setOriginalFileName(imageResult.getOriginalFileName());
            picture.setOriginalFileSize(imageResult.getOriginalFileSize());
            picture.setMimeType(imageResult.getMimeType());
            picture.setStorageKey(imageResult.getFullKey());
            picture.setThumbnailUrl(imageResult.getThumbnailKey());
            picture.setMediumUrl(imageResult.getMediumKey());
            picture.setFullUrl(imageResult.getFullKey());
            picture.setWidth(imageResult.getWidth());
            picture.setHeight(imageResult.getHeight());
            picture.setUploadedFrom("web");
            picture.setIpAddress(ipAddress);
            picture.setIsActive(true);

            profilePictureRepository.save(picture);

            log.info("Profile picture uploaded successfully for user: {}", userId);

            return getUserProfile(userId);

        } catch (Exception e) {
            log.error("Error uploading profile picture", e);
            throw new RuntimeException("Failed to upload profile picture", e);
        }
    }

    /**
     * Change password (requires old password)
     */
    public void changePassword(UUID userId, ChangePasswordRequestDto request) {
        log.info("Password change request for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Check rate limiting (can't change password twice in 1 hour)
        long recentChanges = passwordHistoryRepository.countPasswordChangesInTimeRange(
                userId,
                LocalDateTime.now().minus(minPasswordChangeInterval, ChronoUnit.HOURS)
        );

        if (recentChanges > 0) {
            log.warn("Password change rate limit exceeded for user: {}", userId);
            throw new IllegalArgumentException("Password can only be changed once per hour");
        }

        // Verify old password - a Google-only account has none to verify against.
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new IllegalArgumentException(
                    "This account signs in with Google and has no password to change. Use Google sign-in.");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            log.warn("Incorrect old password for user: {}", userId);
            throw new IllegalArgumentException("Current password is incorrect");
        }

        // Check password strength
        validatePasswordStrength(request.getNewPassword());

        // Check password history (prevent reusing last 5 passwords)
        List<PasswordHistory> lastPasswords = passwordHistoryRepository.findLastNPasswordsForUser(userId);
        for (PasswordHistory history : lastPasswords) {
            if (passwordEncoder.matches(request.getNewPassword(), history.getHashedPassword())) {
                log.warn("User tried to reuse old password: {}", userId);
                throw new IllegalArgumentException("Cannot reuse last 5 passwords");
            }
        }

        // Save old password to history
        PasswordHistory history = PasswordHistory.builder()
                .user(user)
                .hashedPassword(user.getPassword())
                .changedFrom("change")
                .ipAddress(request.getIpAddress())
                .userAgent(request.getUserAgent())
                .build();
        passwordHistoryRepository.save(history);

        // Update password
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setLastPasswordChangeAt(LocalDateTime.now());
        user.setTokenVersion(user.getTokenVersion() + 1); // Invalidate all old tokens
        userRepository.save(user);

        log.info("Password changed successfully for user: {}", userId);

        // Send notification email
        try {
            emailService.sendNotificationEmail(
                    user.getEmail(),
                    "Your Password Has Been Changed",
                    buildPasswordChangeEmailTemplate(user.getFirstName() != null ? user.getFirstName() : user.getUsername())
            );
        } catch (Exception e) {
            log.error("Failed to send password change notification email", e);
        }
    }

    /**
     * Validate password strength
     */
    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        if (!password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[a-zA-Z\\d@$!%*?&]{8,}$")) {
            throw new IllegalArgumentException("Password must contain uppercase, lowercase, number, and special character");
        }
    }

    /**
     * Create default profile for new user
     */
    private UserProfile createDefaultProfile(User user) {
        UserProfile profile = UserProfile.builder()
                .user(user)
                .emailNotifications(true)
                .twoFactorEnabled(false)
                .publicProfile(false)
                .showEmailPublically(false)
                .totalDocumentsProcessed(0)
                .totalAnalysisRequests(0)
                .build();

        return userProfileRepository.save(profile);
    }

    /**
     * Best-effort removal of image objects that are being replaced.
     * <p>
     * Orphaned bytes in a private bucket are a real cost, but failing the user-visible upload
     * because a cleanup step could not find an object (it may already have been purged, or the
     * key may never have been written at all) would be worse. Duplicates are collapsed because
     * {@code storageKey} and {@code fullUrl} intentionally hold the same key.
     */
    private void deleteStoredKeys(String... keys) {
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                distinct.add(key);
            }
        }

        for (String key : distinct) {
            try {
                storageService.deleteFile(key);
            } catch (Exception e) {
                log.warn("Could not delete replaced image object {}: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Delete profile picture
     */
    public void deleteProfilePicture(UUID userId) {
        log.info("Deleting profile picture for user: {}", userId);

        ProfilePicture picture = profilePictureRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("No profile picture found"));

        // The columns are NOT NULL, so the row has to stay; drop the actual objects though,
        // otherwise a deleted avatar keeps costing storage in a bucket nobody can read from.
        deleteStoredKeys(
                picture.getStorageKey(),
                picture.getThumbnailUrl(),
                picture.getMediumUrl(),
                picture.getFullUrl());

        picture.setIsActive(false);
        profilePictureRepository.save(picture);

        log.info("Profile picture deleted for user: {}", userId);
    }

    /**
     * Build password change email template
     */
    private String buildPasswordChangeEmailTemplate(String userName) {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <style>
                body { font-family: Arial, sans-serif; background-color: #f4f4f4; }
                .container { max-width: 600px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; }
                .header { color: #28a745; margin-bottom: 20px; }
                .content { color: #666; line-height: 1.6; }
                .warning { background-color: #fff3cd; padding: 15px; border-radius: 4px; margin: 20px 0; }
                .footer { color: #999; font-size: 12px; margin-top: 20px; border-top: 1px solid #eee; padding-top: 20px; }
            </style>
        </head>
        <body>
            <div class="container">
                <h2 class="header">✓ Password Changed Successfully</h2>

                <div class="content">
                    <p>Hi %s,</p>

                    <p>Your password was successfully changed on %s.</p>

                    <div class="warning">
                        <p><strong>⚠️ Security Note:</strong> If you didn't make this change, your account may be compromised. Please reset your password immediately and enable two-factor authentication.</p>
                    </div>

                    <p>Your security is important to us. All previous sessions have been logged out for safety.</p>
                </div>

                <div class="footer">
                    <p>Content Analytics Platform Team</p>
                    <p>This is an automated message, please do not reply.</p>
                </div>
            </div>
        </body>
        </html>
        """.formatted(userName, LocalDateTime.now());
    }

}
