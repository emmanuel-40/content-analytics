package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")  // ← ONLY LOAD IF S3
@Slf4j
public class S3StorageService {

    @Value("${app.storage.s3.bucket-name}")
    private String bucketName;

    @Value("${app.storage.s3.region:us-east-1}")
    private String region;

    @Value("${app.storage.s3.access-key}")
    private String accessKey;

    @Value("${app.storage.s3.secret-key}")
    private String secretKey;

    @Value("${app.storage.s3.endpoint:}")
    private String endpoint;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    /**
     * Initialize S3 client (lazy initialization)
     */
    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = buildClient();
            log.info("S3 client initialized for bucket: {}", bucketName);
        }
        return s3Client;
    }

    /**
     * Initialize the presigner used for private, time-limited URLs.
     * <p>
     * Built separately from the client because {@code S3Client} has no presigner factory.
     */
    private S3Presigner getS3Presigner() {
        if (s3Presigner == null) {
            S3Presigner.Builder builder = S3Presigner.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));

            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(java.net.URI.create(endpoint));
            }

            s3Presigner = builder.build();
        }
        return s3Presigner;
    }

    private S3Client buildClient() {
            var credentials = AwsBasicCredentials.create(accessKey, secretKey);

            var builder = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials));

            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(java.net.URI.create(endpoint));
            }

            return builder.build();
        }

    /**
     * Upload file to S3
     */
    public String uploadFile(MultipartFile file, UUID userId) {
        try {
            String originalFilename = file.getOriginalFilename();
            String fileExtension = getFileExtension(originalFilename);
            String uniqueFilename = UUID.randomUUID() + "." + fileExtension;

            // Create S3 key with folder structure: documents/userId/YYYY/MM/DD/filename
            String dateFolder = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String s3Key = String.format("documents/%s/%s/%s", userId.toString(), dateFolder, uniqueFilename);  // ← Convert to String

            // Upload to S3
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .metadata(java.util.Map.of(
                            "original-filename", originalFilename,
                            "uploaded-by", userId.toString(),  // ← Convert to String
                            "uploaded-at", LocalDateTime.now().toString()
                    ))
                    .build();

            // Use the lazily-initialised client accessor, not the raw field
            getS3Client().putObject(putObjectRequest, RequestBody.fromInputStream(
                    file.getInputStream(),
                    file.getSize()
            ));

            log.info("File uploaded to S3: {}", s3Key);
            return s3Key;

        } catch (Exception e) {
            log.error("Error uploading file to S3", e);
            throw new RuntimeException("Failed to upload file", e);
        }
    }


    /**
     * Upload raw bytes to an explicit key (used by the image pipeline, which derives its own
     * deterministic keys instead of letting this layer mint a random filename).
     */
    public String uploadBytes(String fileKey, byte[] bytes, String contentType) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build();

            getS3Client().putObject(putObjectRequest, RequestBody.fromBytes(bytes));

            log.info("Bytes uploaded to S3: {}", fileKey);
            return fileKey;

        } catch (Exception e) {
            log.error("Error uploading bytes to S3", e);
            throw new RuntimeException("Failed to upload file", e);
        }
    }


    /**
     * Delete file from S3
     */
    public void deleteFile(String fileKey) {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();

            getS3Client().deleteObject(deleteObjectRequest);
            log.info("File deleted from S3: {}", fileKey);

        } catch (S3Exception e) {
            log.error("Error deleting file from S3: {}", fileKey, e);
            throw new RuntimeException("Failed to delete file from S3", e);
        }
    }

    /**
     * Generate a time-limited, signed URL for a private object.
     * <p>
     * This is how a private bucket stays private while still being renderable in a browser:
     * the backend hands out a short-lived URL instead of a permanent public one, so a leaked
     * link stops working on its own.
     * <p>
     * Supabase Storage supports presigning via query parameters using AWS Signature Version 4,
     * which is exactly what the SDK produces.
     *
     * @param fileKey        storage key of the object
     * @param duration       how long the URL should remain valid
     * @return a signed URL, or {@code null} if presigning failed
     */
public String generatePresignedUrl(String fileKey, java.time.Duration duration) {
    try {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .build();

        var presignRequest = software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getObjectRequest)
                .build();

        return getS3Presigner().presignGetObject(presignRequest).url().toString();

    } catch (Exception e) {
        log.error("Could not presign URL for {}: {}", fileKey, e.getMessage());
        return null;
    }
}

/**
     * Get a direct object URL.
     * <p>
     * Deprecated: this only resolves for a PUBLIC bucket. On a private bucket objects must be
     * served with {@link #generatePresignedUrl(String, java.time.Duration)}. No application
     * code calls this - document downloads are streamed through the backend instead.
     */
    @Deprecated
    public String getFileUrl(String fileKey) {
        log.warn("getFileUrl is deprecated and unsafe on a private bucket - use generatePresignedUrl");
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, fileKey);
    }

    /**
     * Check if file exists in S3
     */
    public boolean fileExists(String fileKey) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();

            getS3Client().headObject(headObjectRequest);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.error("Error checking if file exists in S3: {}", fileKey, e);
            return false;
        }
    }

    /**
     * Download a file from S3 into memory so it can be streamed to the client.
     * <p>
     * S3 does not expose a seekable stream through the sync client, so the object is buffered.
     * This is bounded by the configured maximum upload size.
     */
    public Resource loadFileAsResource(String fileKey) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();

            byte[] content = getS3Client()
                    .getObjectAsBytes(getObjectRequest)
                    .asByteArray();

            return new ByteArrayResource(content);

        } catch (S3Exception e) {
            log.error("Error loading file from S3: {}", fileKey, e);
            throw new RuntimeException("Failed to load file from S3", e);
        }
    }

    /**
     * Get file extension
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

}