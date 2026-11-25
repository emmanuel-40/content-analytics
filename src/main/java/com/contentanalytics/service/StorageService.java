package com.contentanalytics.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    @Value("${app.storage.type:local}")
    private String storageType;

    @Value("${app.storage.local.path:/tmp/content-analytics/uploads}")
    private String localStoragePath;

    @Value("${app.storage.local.base-url:http://localhost:8080/api/documents/files}")
    private String localBaseUrl;

    private final Optional<S3StorageService> s3StorageService;  // ← OPTIONAL (not required)

    /**
     * Select the backend once, with a clear configuration error for unsupported values.
     * {@code supabase} is production's name for the same S3-compatible API the SDK speaks;
     * it is accepted as an alias for {@code s3}.
     */
    private boolean useS3() {
        String type = storageType == null ? "local" : storageType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "local" -> false;
            case "s3", "supabase" -> true;
            default -> throw new IllegalStateException(
                    "Unsupported app.storage.type '" + storageType
                            + "'. Supported values: local, s3, supabase (S3-compatible)");
        };
    }

    private S3StorageService s3Storage() {
        return s3StorageService.orElseThrow(() -> new IllegalStateException(
                "app.storage.type=" + storageType + " requires S3StorageService; "
                        + "check the app.storage.type and S3 credentials configuration"));
    }

    /** Fail at startup for an unsupported backend rather than on the first file operation. */
    @PostConstruct
    void validateStorageType() {
        useS3();
    }

    /**
     * Read an object's bytes through the active backend. Used by text extraction so the
     * worker never assumes documents live on the local filesystem.
     */
    public byte[] loadFileBytes(String fileKey) {
        if (useS3()) {
            try {
                return s3Storage().loadFileAsResource(fileKey).getContentAsByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read stored file: " + fileKey, e);
            }
        }
        try {
            return Files.readAllBytes(resolveLocalPath(fileKey));
        } catch (IOException e) {
            log.error("Error reading file locally for key {}", fileKey, e);
            throw new RuntimeException("Failed to read file", e);
        }
    }

    /**
     * Resolve a key inside the configured local root, rejecting traversal attempts.
     */
    private Path resolveLocalPath(String fileKey) {
        Path root = Paths.get(localStoragePath).toAbsolutePath().normalize();
        Path filePath = Paths.get(localStoragePath, fileKey).normalize();
        if (!filePath.toAbsolutePath().normalize().startsWith(root)) {
            log.error("Rejected path traversal attempt for file key: {}", fileKey);
            throw new IllegalArgumentException("Invalid file key");
        }
        return filePath;
    }

    /**
     * Save file to storage (local or S3)
     */
    public String saveFile(MultipartFile file, UUID userId) {
        if (useS3()) {
            return s3Storage().uploadFile(file, userId);
        } else {
            return saveFileLocally(file, userId);
        }
    }

    /**
     * Delete file from storage
     */
    public void deleteFile(String fileKey) {
        if (useS3()) {
            s3Storage().deleteFile(fileKey);
        } else {
            deleteFileLocally(fileKey);
        }
    }

    /**
     * Generate a short-lived signed URL for a stored object.
     *
     * Used so a private bucket never has to be made public: the client receives a temporary
     * link instead of a permanent one.
     */
    public String generatePresignedUrl(String fileKey, java.time.Duration duration) {
        if (useS3()) {
            return s3Storage().generatePresignedUrl(fileKey, duration);
        }
        // Local files are served through the authenticated download endpoint, so no URL is needed.
        return null;
    }

    /**
     * Persist raw bytes under an explicitly-chosen key.
     *
     * The image pipeline mints its own deterministic keys
     * ({@code profile-{userId}-{size}-{timestamp}.jpg}) because those exact strings are what
     * gets stored in the {@code profile_pictures} columns and later turned into signed URLs.
     * {@link #saveFile} deliberately generates its own random filename, so it cannot be used
     * there.
     */
    public String saveBytes(String fileKey, byte[] bytes, String contentType) {
        if (useS3()) {
            return s3Storage().uploadBytes(fileKey, bytes, contentType);
        }
        saveBytesLocally(fileKey, bytes);
        return fileKey;
    }

    /**
     * Write bytes under {@code fileKey}, refusing anything that escapes the storage root.
     */
    private void saveBytesLocally(String fileKey, byte[] bytes) {
        Path filePath = resolveLocalPath(fileKey);

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, bytes);
            log.debug("Bytes written locally: {}", fileKey);
        } catch (IOException e) {
            log.error("Error writing bytes locally for key {}", fileKey, e);
            throw new RuntimeException("Failed to write file", e);
        }
    }

    /**
     * Legacy direct URL helper. Only meaningful for local storage or a public bucket;
     * production document downloads use the authenticated streaming endpoint.
     */
    public String getFileUrl(String fileKey) {
        if (useS3()) {
            return s3Storage().getFileUrl(fileKey);
        } else {
            return localBaseUrl + "/" + fileKey;
        }
    }

    /**
     * Load a stored file as a Spring {@link Resource} for streaming back to a client.
     *
     * S3 keys are fetched into memory (bounded by the configured upload cap); local files are
     * returned as a filesystem resource so large documents stream without buffering.
     */
    public org.springframework.core.io.Resource loadFileAsResource(String fileKey) {
        if (useS3()) {
            return s3Storage().loadFileAsResource(fileKey);
        }
        // resolveLocalPath also guards against traversal outside the storage root.
        return new org.springframework.core.io.FileSystemResource(resolveLocalPath(fileKey));
    }

    /**
     * Save file to local filesystem
     */
    private String saveFileLocally(MultipartFile file, UUID userId) {
        try {
            // Create directory structure: /uploads/userId/YYYY/MM/
            Path userStoragePath = Paths.get(localStoragePath, String.valueOf(userId));
            Files.createDirectories(userStoragePath);

            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String fileExtension = getFileExtension(originalFilename);
            String uniqueFilename = UUID.randomUUID() + "." + fileExtension;

            // Full file path
            Path filePath = userStoragePath.resolve(uniqueFilename);

            // Save file
            Files.write(filePath, file.getBytes());

            log.info("File saved locally: {} for user: {}", uniqueFilename, userId);

            // Return file key
            return userId + "/" + uniqueFilename;

        } catch (IOException e) {
            log.error("Error saving file locally", e);
            throw new RuntimeException("Failed to save file", e);
        }
    }

    /**
     * Delete file from local filesystem
     */
    private void deleteFileLocally(String fileKey) {
        try {
            Path filePath = resolveLocalPath(fileKey);
            Files.deleteIfExists(filePath);
            log.info("File deleted locally: {}", fileKey);
        } catch (IOException e) {
            log.error("Error deleting file locally: {}", fileKey, e);
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    /**
     * Get file extension from filename
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

}
