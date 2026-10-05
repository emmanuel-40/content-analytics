package com.contentanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StorageService")
class StorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(Optional.empty());
        ReflectionTestUtils.setField(storageService, "storageType", "local");
        ReflectionTestUtils.setField(storageService, "localStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(storageService, "localBaseUrl", "http://localhost:8080/api/documents/files");
    }

    @Test
    @DisplayName("saves a file under the user's key and it can be read back")
    void savedFileCanBeLoadedBack() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello world".getBytes());

        String fileKey = storageService.saveFile(file, java.util.UUID.randomUUID());

        Resource resource = storageService.loadFileAsResource(fileKey);
        assertThat(resource.exists()).isTrue();
        assertThat(new String(resource.getContentAsByteArray())).isEqualTo("hello world");
    }

    @Test
    @DisplayName("getFileUrl points at the configured local base url")
    void getFileUrlUsesConfiguredBaseUrl() {
        assertThat(storageService.getFileUrl("user-1/doc.txt"))
                .isEqualTo("http://localhost:8080/api/documents/files/user-1/doc.txt");
    }

    @Test
    @DisplayName("rejects path traversal attempts in file keys")
    void pathTraversalIsRejected() {
        assertThatThrownBy(() -> storageService.loadFileAsResource("../../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid file key");
    }

    @Test
    @DisplayName("deletes a stored file idempotently")
    void deleteIsIdempotent() throws Exception {
        Files.createDirectories(tempDir.resolve("user-1"));
        Path file = tempDir.resolve("user-1").resolve("doc.txt");
        Files.writeString(file, "content");

        storageService.deleteFile("user-1/doc.txt");

        assertThat(file).doesNotExist();
        // Deleting again must not throw
        storageService.deleteFile("user-1/doc.txt");
    }

}