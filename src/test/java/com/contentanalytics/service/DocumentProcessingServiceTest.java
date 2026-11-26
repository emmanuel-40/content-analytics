package com.contentanalytics.service;

import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.service.DocumentService.ClaimedDocument;
import com.contentanalytics.service.DocumentService.ProcessedDocument;
import com.contentanalytics.service.JobQueue.DocumentJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Document processing cleanup")
class DocumentProcessingServiceTest {

    @Mock private WebSocketService webSocketService;
    @Mock private DocumentService documentService;
    @Mock private FileExtractionService fileExtractionService;
    @Mock private AiAnalysisService aiAnalysisService;
    @Mock private CreditService creditService;
    @Mock private StorageService storageService;

    @InjectMocks private DocumentProcessingService processingService;

    @Test
    @DisplayName("successful analysis deletes the source file and caps stored text")
    void successfulAnalysisDeletesSourceFile() {
        UUID documentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String fileKey = userId + "/source.pdf";
        String extractedText = "a".repeat(25000);

        when(documentService.claimForProcessing(documentId)).thenReturn(
                new ClaimedDocument(documentId, userId, "source.pdf", fileKey, "application/pdf"));
        when(fileExtractionService.extractText(fileKey, "application/pdf")).thenReturn(extractedText);
        when(aiAnalysisService.isEnabled()).thenReturn(false);
        when(documentService.updateProcessingStatus(documentId, DocumentStatus.PROCESSING, 30f, null))
                .thenReturn(true);
        when(documentService.updateProcessingStatus(documentId, DocumentStatus.PROCESSING, 70f, null))
                .thenReturn(true);
        when(documentService.completeProcessing(eq(documentId), any(ProcessedDocument.class)))
                .thenReturn(true);

        assertThat(processingService.processJob(new DocumentJob(jobId, documentId, userId, 1)))
                .isEqualTo(DocumentProcessingService.JobOutcome.COMPLETED);

        ArgumentCaptor<ProcessedDocument> resultCaptor = ArgumentCaptor.forClass(ProcessedDocument.class);
        verify(documentService).completeProcessing(eq(documentId), resultCaptor.capture());
        ProcessedDocument result = resultCaptor.getValue();
        assertThat(result.characterCount()).isEqualTo(25000);
        assertThat(result.wordCount()).isEqualTo(1);
        assertThat(result.extractedText()).endsWith("[truncated for storage]");
        assertThat(result.extractedText().length()).isLessThan(25000);

        verify(storageService).deleteFile(fileKey);
        verify(documentService).markSourceFileDeleted(documentId);
        verify(webSocketService).sendDocumentCompleted(
                eq(userId), eq(documentId), eq("source.pdf"), any(), any(), any(),
                any(), eq(1), any(Long.class));
    }

    @Test
    @DisplayName("failed analysis keeps the source file for retry")
    void failedAnalysisKeepsSourceFile() {
        UUID documentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String fileKey = userId + "/source.pdf";

        when(documentService.claimForProcessing(documentId)).thenReturn(
                new ClaimedDocument(documentId, userId, "source.pdf", fileKey, "application/pdf"));
        when(fileExtractionService.extractText(fileKey, "application/pdf"))
                .thenThrow(new IllegalStateException("extraction failed"));
        when(documentService.failProcessing(eq(documentId), anyString())).thenReturn(true);

        assertThat(processingService.processJob(
                new DocumentJob(UUID.randomUUID(), documentId, userId, 1)))
                .isEqualTo(DocumentProcessingService.JobOutcome.FAILED);

        verify(storageService, never()).deleteFile(anyString());
        verify(documentService, never()).markSourceFileDeleted(any());
        verify(webSocketService).sendDocumentFailed(
                eq(userId), eq(documentId), eq("source.pdf"), anyString(), eq("PROCESSING_ERROR"));
    }

}
