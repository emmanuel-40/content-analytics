package com.contentanalytics.controller;

import com.contentanalytics.dto.*;
import com.contentanalytics.service.ProfileService;
import com.contentanalytics.service.StorageService;
import com.contentanalytics.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController("userProfileController")
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

    private final ProfileService profileService;
    private final SecurityUtil securityUtil;
    private final StorageService storageService;

    /**
     * GET /api/profile
     * Get authenticated user's complete profile
     */
    @GetMapping
    public ResponseEntity<UserProfileDto> getProfile() {
        UUID userId = securityUtil.getCurrentUserId();  // ← USE SecurityUtil
        log.debug("Fetching profile for user: {}", userId);

        UserProfileDto profile = profileService.getUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    /**
     * PUT /api/profile
     * Update authenticated user's profile information
     */
    @PutMapping
    public ResponseEntity<UserProfileDto> updateProfile(
            @Valid @RequestBody UpdateProfileRequestDto request) {

        UUID userId = securityUtil.getCurrentUserId();  // ← USE SecurityUtil
        log.info("Profile update request from user: {}", userId);

        try {
            UserProfileDto updatedProfile = profileService.updateProfile(userId, request);
            return ResponseEntity.ok(updatedProfile);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid profile update: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /api/profile/picture
     * Upload profile picture
     */
    @PostMapping("/picture")
    public ResponseEntity<UserProfileDto> uploadProfilePicture(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {

        UUID userId = securityUtil.getCurrentUserId();  // ← USE SecurityUtil
        String ipAddress = getClientIp(httpRequest);

        log.info("Profile picture upload request from user: {}", userId);

        try {
            UserProfileDto profile = profileService.uploadProfilePicture(userId, file, ipAddress);
            log.info("Profile picture uploaded successfully for user: {}", userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(profile);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid image file: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Error uploading profile picture", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * DELETE /api/profile/picture
     * Delete profile picture
     */
    @DeleteMapping("/picture")
    public ResponseEntity<ProfilePictureDeleteResponseDto> deleteProfilePicture() {

        UUID userId = securityUtil.getCurrentUserId();  // ← USE SecurityUtil
        log.info("Delete profile picture request from user: {}", userId);

        try {
            profileService.deleteProfilePicture(userId);
            UserProfileDto updatedProfile = profileService.getUserProfile(userId);

            ProfilePictureDeleteResponseDto response = ProfilePictureDeleteResponseDto.builder()
                    .status("SUCCESS")
                    .message("Profile picture deleted successfully")
                    .avatarUrl(updatedProfile.getAvatarUrl())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting profile picture", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProfilePictureDeleteResponseDto.builder()
                            .status("ERROR")
                            .message(e.getMessage())
                            .build());
        }
    }

    /**
     * GET /api/profile/picture/file?key={storageKey}
     * Stream a stored profile-picture variant when storage is local.
     * <p>
     * Only used by {@code local} storage; on S3/Supabase the client gets a presigned URL
     * instead. Kept authenticated and ownership-checked so a private filesystem stays private
     * too.
     * <p>
     * ImageService names every variant {@code profile-{userId}-{size}-{timestamp}.jpg}, so the
     * caller's own UUID is baked into the key. We only ever serve keys in that namespace --
     * anything else (other users' keys, path traversal) is rejected before touching disk.
     */
    @GetMapping("/picture/file")
    public ResponseEntity<org.springframework.core.io.Resource> getProfilePictureFile(
            @RequestParam("key") String key) {

        UUID userId = securityUtil.getCurrentUserId();

        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Ownership check: the key must be in this caller's own profile-picture namespace.
        if (!key.startsWith("profile-" + userId + "-")) {
            log.warn("User {} attempted to read a picture key outside their namespace", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            org.springframework.core.io.Resource resource = storageService.loadFileAsResource(key);
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(guessContentType(key))
                    .body(resource);

        } catch (IllegalArgumentException e) {
            log.warn("Rejected picture key from user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error streaming profile picture for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static MediaType guessContentType(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        return MediaType.IMAGE_JPEG;
    }

    /**
     * POST /api/profile/change-password
     * Change user's password
     */
    @PostMapping("/change-password")
    public ResponseEntity<PasswordChangeResponseDto> changePassword(
            @Valid @RequestBody ChangePasswordRequestDto request,
            HttpServletRequest httpRequest) {

        UUID userId = securityUtil.getCurrentUserId();  // ← USE SecurityUtil
        log.info("Password change request from user: {}", userId);

        if (!request.isPasswordConfirmed()) {
            log.warn("Password confirmation mismatch for user: {}", userId);
            return ResponseEntity.badRequest()
                    .body(PasswordChangeResponseDto.builder()
                            .status("ERROR")
                            .message("Passwords do not match")
                            .build());
        }

        try {
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            request.setIpAddress(ipAddress);
            request.setUserAgent(userAgent);

            profileService.changePassword(userId, request);

            PasswordChangeResponseDto response = PasswordChangeResponseDto.builder()
                    .status("SUCCESS")
                    .message("Password changed successfully. All sessions have been logged out for security.")
                    .changedAt(LocalDateTime.now())
                    .allSessionsLoggedOut(true)
                    .build();

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Password change validation error: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(PasswordChangeResponseDto.builder()
                            .status("ERROR")
                            .message(e.getMessage())
                            .build());
        }
    }

    /**
     * GET /api/profile/validate-field/{field}
     * Validate a profile field
     */
    @GetMapping("/validate-field/{field}")
    public ResponseEntity<ProfileValidationResponseDto> validateField(
            @PathVariable String field,
            @RequestParam String value) {

        log.debug("Field validation request for: {}", field);
        ProfileValidationResponseDto response = validateProfileField(field, value);
        return ResponseEntity.ok(response);
    }

    /**
     * Helper method to validate individual profile fields
     */
    private ProfileValidationResponseDto validateProfileField(String field, String value) {
        return switch (field) {
            case "website" -> {
                boolean valid = value == null || value.isEmpty() ||
                        value.matches("^(https?://)?([\\da-z\\.-]+)\\.([a-z\\.]{2,6})([/\\w \\.-]*)*/?$");
                yield ProfileValidationResponseDto.builder()
                        .field(field)
                        .valid(valid)
                        .message(valid ? "Valid website URL" : "Invalid website URL format")
                        .build();
            }
            case "phoneNumber" -> {
                boolean valid = value == null || value.isEmpty() ||
                        value.matches("^[+]?[0-9]{1,20}$");
                yield ProfileValidationResponseDto.builder()
                        .field(field)
                        .valid(valid)
                        .message(valid ? "Valid phone number" : "Invalid phone number format")
                        .build();
            }
            case "timezone" -> {
                boolean valid = isValidTimezone(value);
                yield ProfileValidationResponseDto.builder()
                        .field(field)
                        .valid(valid)
                        .message(valid ? "Valid timezone" : "Invalid timezone")
                        .build();
            }
            default -> ProfileValidationResponseDto.builder()
                    .field(field)
                    .valid(true)
                    .message("Field valid")
                    .build();
        };
    }

    /**
     * Check if timezone is valid
     */
    private boolean isValidTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return true;
        }
        try {
            java.time.ZoneId.of(timezone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get client IP address from request
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}