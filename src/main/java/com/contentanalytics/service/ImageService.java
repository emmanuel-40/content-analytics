package com.contentanalytics.service;

import com.contentanalytics.dto.ImageProcessingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageService {

    private final StorageService storageService;

    @Value("${app.image.max-size:5242880}") // 5MB
    private long maxImageSize;

    @Value("${app.image.allowed-types:jpeg,jpg,png,webp}")
    private String allowedImageTypes;

    /**
     * Process and upload profile picture
     * Creates 3 sizes: thumbnail (150x150), medium (400x400), full (800x800)
     */
    public ImageProcessingResult processProfilePicture(MultipartFile file, UUID userId) {
        log.info("Processing profile picture for user: {}", userId);

        try {
            // Validate file
            validateImage(file);

            // Read original image
            BufferedImage originalImage = ImageIO.read(file.getInputStream());
            if (originalImage == null) {
                throw new RuntimeException("Failed to read image file");
            }

            // Crop to square (center crop)
            BufferedImage squareImage = cropToSquare(originalImage);
            log.debug("Image cropped to square: {}x{}", squareImage.getWidth(), squareImage.getHeight());

            // Create 3 sizes
            BufferedImage thumbnail = resizeImage(squareImage, 150, 150);
            BufferedImage medium = resizeImage(squareImage, 400, 400);
            BufferedImage full = resizeImage(squareImage, 800, 800);

            log.debug("Images resized to: 150x150, 400x400, 800x800");

            // Convert to bytes and upload
            byte[] thumbnailBytes = imageToBytes(thumbnail, "jpeg");
            byte[] mediumBytes = imageToBytes(medium, "jpeg");
            byte[] fullBytes = imageToBytes(full, "jpeg");

            // Create MockMultipartFile for upload
            String timestamp = String.valueOf(System.currentTimeMillis());
            String thumbnailKey = uploadImage(userId, "thumbnail", thumbnailBytes, timestamp);
            String mediumKey = uploadImage(userId, "medium", mediumBytes, timestamp);
            String fullKey = uploadImage(userId, "full", fullBytes, timestamp);

            log.info("Profile picture uploaded successfully for user: {}", userId);

            return ImageProcessingResult.builder()
                    .originalFileName(file.getOriginalFilename())
                    .originalFileSize(file.getSize())
                    .mimeType(file.getContentType())
                    .thumbnailKey(thumbnailKey)
                    .mediumKey(mediumKey)
                    .fullKey(fullKey)
                    .width(full.getWidth())
                    .height(full.getHeight())
                    .build();

        } catch (IOException e) {
            log.error("Error processing profile picture", e);
            throw new RuntimeException("Failed to process image", e);
        }
    }

    /**
     * Validate image file
     */
    private void validateImage(MultipartFile file) {
        // Check file size
        if (file.getSize() > maxImageSize) {
            throw new IllegalArgumentException("Image size exceeds maximum allowed size of " + maxImageSize + " bytes");
        }

        // Check file type
        String filename = file.getOriginalFilename();
        String extension = getFileExtension(filename).toLowerCase();

        String[] allowed = allowedImageTypes.split(",");
        boolean isAllowed = Arrays.asList(allowed).contains(extension);

        if (!isAllowed) {
            throw new IllegalArgumentException("Image type ." + extension + " is not allowed. Allowed types: " + allowedImageTypes);
        }

        // Check MIME type
        String mimeType = file.getContentType();
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("File is not a valid image");
        }
    }

    /**
     * Crop image to square (center crop)
     */
    private BufferedImage cropToSquare(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width == height) {
            return image;
        }

        int size = Math.min(width, height);
        int x = (width - size) / 2;
        int y = (height - size) / 2;

        return image.getSubimage(x, y, size, size);
    }

    /**
     * Resize image to target dimensions
     */
    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();

        // Set rendering hints for better quality
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g2d.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();

        return resized;
    }

    /**
     * Convert BufferedImage to bytes (JPEG with compression)
     */
    private byte[] imageToBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format.toUpperCase(), baos);
        return baos.toByteArray();
    }

    /**
     * Upload image to storage
     */
    private String uploadImage(UUID userId, String size, byte[] imageBytes, String timestamp) {
        String fileKey = String.format("profile-%s-%s-%s.jpg", userId, size, timestamp);

        // Persist the bytes. This used to just return the filename, so avatars were recorded
        // in profile_pictures but never written to storage -- every later read 404'd.
        storageService.saveBytes(fileKey, imageBytes, "image/jpeg");

        return fileKey;
    }

    /**
     * Get file extension
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

}