package com.contentanalytics.scheduler;

import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Retry path for source-file cleanup.
 *
 * The processing worker deletes the upload immediately after analysis. If Supabase is
 * briefly unavailable, the flag stays false and this job removes the leftover object
 * without touching the completed analysis results.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentSourceCleanupScheduler {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;

    @Value("${app.document.cleanup.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${app.document.cleanup.initial-delay-ms:3600000}",
            fixedDelayString = "${app.document.cleanup.interval-ms:3600000}")
    public void cleanupStoredSourceFiles() {
        int size = Math.max(1, batchSize);
        // One hour of grace avoids racing a worker that just committed COMPLETED.
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);

        List<Document> candidates = documentRepository.findSourceCleanupCandidates(
                DocumentStatus.COMPLETED, cutoff, PageRequest.of(0, size));

        if (candidates.isEmpty()) {
            return;
        }

        log.info("Retrying source-file cleanup for {} completed document(s)", candidates.size());
        for (Document document : candidates) {
            cleanupDocument(document);
        }
    }

    private void cleanupDocument(Document document) {
        try {
            storageService.deleteFile(document.getFileKey());
            int updated = documentRepository.markSourceFileDeleted(
                    document.getId(), DocumentStatus.COMPLETED, LocalDateTime.now());
            if (updated == 0) {
                log.debug("Source cleanup flag was already set or document changed: {}", document.getId());
            } else {
                log.info("Source file cleanup completed for document: {}", document.getId());
            }
        } catch (RuntimeException e) {
            log.error("Source file cleanup retry failed for document {} (key: {})",
                    document.getId(), document.getFileKey(), e);
        }
    }

}
