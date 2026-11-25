package com.contentanalytics.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StorageService backend selection")
class StorageServiceBackendSelectionTest {

    @Test
    @DisplayName("supabase is accepted as an S3-compatible backend (case-insensitive)")
    void supabaseUsesS3Backend() {
        S3StorageService s3 = mock(S3StorageService.class);
        when(s3.loadFileAsResource("documents/key.txt"))
                .thenReturn(new ByteArrayResource("hello".getBytes(StandardCharsets.UTF_8)));

        StorageService service = new StorageService(Optional.of(s3));
        ReflectionTestUtils.setField(service, "storageType", "SUPABASE");

        assertThat(service.loadFileBytes("documents/key.txt")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        verify(s3).loadFileAsResource("documents/key.txt");
    }

    @Test
    @DisplayName("an unknown storage type fails fast with an actionable error")
    void unknownBackendFailsFast() {
        StorageService service = new StorageService(Optional.empty());
        ReflectionTestUtils.setField(service, "storageType", "gcs");

        assertThatThrownBy(() -> service.saveBytes("key", new byte[]{1}, "application/octet-stream"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported app.storage.type");
    }

    @Test
    @DisplayName("selecting S3 storage without the S3 bean fails clearly")
    void s3BackendWithoutBeanFailsClearly() {
        StorageService service = new StorageService(Optional.empty());
        ReflectionTestUtils.setField(service, "storageType", "s3");

        assertThatThrownBy(() -> service.loadFileBytes("documents/key.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires S3StorageService");
    }

}
