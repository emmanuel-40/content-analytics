package com.contentanalytics.scheduler;

import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.service.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Document source cleanup scheduler")
class DocumentSourceCleanupSchedulerTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private StorageService storageService;

    @InjectMocks private DocumentSourceCleanupScheduler scheduler;

    private Document completedDocument() {
        return Document.builder()
                .id(UUID.randomUUID())
                .fileKey(UUID.randomUUID() + "/source.pdf")
                .status(DocumentStatus.COMPLETED)
                .build();
    }

    @Test
    @DisplayName("deletes a leftover file and marks it cleaned")
    void deletesLeftoverFile() {
        Document document = completedDocument();
        when(documentRepository.findSourceCleanupCandidates(
                eq(DocumentStatus.COMPLETED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(document));
        when(documentRepository.markSourceFileDeleted(
                eq(document.getId()), eq(DocumentStatus.COMPLETED), any(LocalDateTime.class)))
                .thenReturn(1);

        scheduler.cleanupStoredSourceFiles();

        verify(storageService).deleteFile(document.getFileKey());
        verify(documentRepository).markSourceFileDeleted(
                eq(document.getId()), eq(DocumentStatus.COMPLETED), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("does not mark cleaned when storage deletion fails")
    void keepsFlagUnsetWhenStorageFails() {
        Document document = completedDocument();
        when(documentRepository.findSourceCleanupCandidates(
                eq(DocumentStatus.COMPLETED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(document));
        doThrow(new RuntimeException("supabase unavailable"))
                .when(storageService).deleteFile(document.getFileKey());

        scheduler.cleanupStoredSourceFiles();

        verify(documentRepository, never()).markSourceFileDeleted(
                any(UUID.class), any(DocumentStatus.class), any(LocalDateTime.class));
    }

}
