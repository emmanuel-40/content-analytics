package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * Save file to storage (local or S3)
     */
    public String saveFile(MultipartFile file, UUID userId) {
        if ("s3".equalsIgnoreCase(storageType)) {
            return s3StorageService.get().uploadFile(file, userId);
        } else {
            return saveFileLocally(file, userId);
        }
    }

    /**
     * Delete file from storage
     */
    public void deleteFile(String fileKey) {
        if ("s3".equalsIgnoreCase(storageType)) {
            s3StorageService.get().deleteFile(fileKey);
        } else {
            deleteFileLocally(fileKey);
        }
    }

    /**
     * Generate a short-lived signed URL for a stored object.
     * <p>
     * Used so a private bucket never has to be made public: the client receives a temporary
     * link instead of a permanent one.
     */
    public String generatePresignedUrl(String fileKey, java.time.Duration duration) {
        if ("s3".equalsIgnoreCase(storageType)) {
            return s3StorageService.get().generatePresignedUrl(fileKey, duration);
        }
        // Local files are served through the authenticated download endpoint, so no URL is needed.
        return null;
    }

    /**
     * Persist raw bytes under an explicitly-chosen key.
     * <p>
     * The image pipeline mints its own deterministic keys
     * ({@code profile-{userId}-{size}-{timestamp}.jpg}) because those exact strings are what
     * gets stored in the {@code profile_pictures} columns and later turned into signed URLs.
     * {@link #saveFile} deliberately generates its own random filename, so it cannot be used
     * there.
     */
    public String saveBytes(String fileKey, byte[] bytes, String contentType) {
        if ("s3".equalsIgnoreCase(storageType)) {
            return s3StorageService.get().uploadBytes(fileKey, bytes, contentType);
        }
        saveBytesLocally(fileKey, bytes);
        return fileKey;
    }

    /**
     * Write bytes under {@code fileKey}, refusing anything that escapes the storage root.
     */
    private void saveBytesLocally(String fileKey, byte[] bytes) {
        Path root = Paths.get(localStoragePath).toAbsolutePath().normalize();
        Path filePath = Paths.get(localStoragePath, fileKey).normalize();

        if (!filePath.toAbsolutePath().normalize().startsWith(root)) {
            log.error("Rejected path traversal attempt for file key: {}", fileKey);
            throw new IllegalArgumentException("Invalid file key");
        }

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
     * Get file download URL
     *
     * @deprecated only meaningful for a public bucket; prefer the authenticated download endpoint
     */
    @Deprecated
    public String getFileUrl(String fileKey) {
        if ("s3".equalsIgnoreCase(storageType)) {
            return s3StorageService.get().getFileUrl(fileKey);
        } else {
            return localBaseUrl + "/" + fileKey;
        }
    }

    /**
     * Load a stored file as a Spring {@link Resource} for streaming back to a client.
     * <p>
     * S3 keys are fetched into memory (bounded by the configured upload cap); local files are
     * returned as a filesystem resource so large documents stream without buffering.
     */
public org.springframework.core.io.Resource loadFileAsResource(String fileKey) {
        if ("s3".equalsIgnoreCase(storageType)) {
            return s3StorageService.get().loadFileAsResource(fileKey);
        }
        Path filePath = Paths.get(localStoragePath, fileKey).normalize();

        // Defence in depth: ensure the resolved path stays inside the configured storage root,
        // so a crafted file key cannot traverse outside it.
        Path root = Paths.get(localStoragePath).toAbsolutePath().normalize();
        if (!filePath.toAbsolutePath().normalize().startsWith(root)) {
            log.error("Rejected path traversal attempt for file key: {}", fileKey);
            throw new IllegalArgumentException("Invalid file key");
        }

        return new org.springframework.core.io.FileSystemResource(filePath);
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
            Path filePath = Paths.get(localStoragePath, fileKey);
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