package com.contentanalytics.service;

import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.DocumentService.ClaimedDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the atomic claim against H2 (no Testcontainers), using the real Spring context
 * and the same {@code test} profile as the other integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Document job claim/concurrency")
class DocumentJobConcurrencyTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .username("claim-" + UUID.randomUUID().toString().substring(0, 8))
                .email("claim-" + UUID.randomUUID() + "@example.com")
                .password("$2a$10$placeholderHashPlaceholderPlaceholderPlaceholder")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .build());
    }

    private UUID createDocument() {
        return documentRepository.save(Document.builder()
                .fileName("notes.txt")
                .fileKey(UUID.randomUUID() + "/notes.txt")
                .fileSize(11L)
                .mimeType("text/plain")
                .pageCount(0)
                .status(DocumentStatus.UPLOADED)
                .processingProgress(0f)
                .user(user)
                .build()).getId();
    }

    @Test
    @DisplayName("only the first worker can claim an UPLOADED document")
    void onlyOneWorkerClaims() {
        UUID documentId = createDocument();

        ClaimedDocument first = documentService.claimForProcessing(documentId);

        assertThat(first).isNotNull();
        assertThat(first.id()).isEqualTo(documentId);
        assertThat(first.userId()).isEqualTo(user.getId());
        assertThat(documentRepository.findByIdAndStatusForUpdate(documentId, DocumentStatus.PROCESSING))
                .isPresent();
        assertThat(documentService.claimForProcessing(documentId)).isNull();
    }

    @Test
    @DisplayName("a reset document can be claimed again without losing the version bump")
    void resetMakesDocumentClaimableAgain() {
        UUID documentId = createDocument();

        documentService.claimForProcessing(documentId);
        documentService.resetForRetry(documentId, "transient failure");
        // The bulk reset updates the DB directly; drop the stale managed instance.
        entityManager.flush();
        entityManager.clear();

        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.UPLOADED);
        assertThat(documentService.claimForProcessing(documentId)).isNotNull();
        assertThat(documentRepository.findById(documentId).orElseThrow().getVersion())
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("deletion is rejected while a worker owns the row")
    void deleteRejectedWhileProcessing() {
        UUID documentId = createDocument();
        documentService.claimForProcessing(documentId);

        assertThatThrownBy(() -> documentService.deleteDocument(documentId, user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being processed");
    }

    @Test
    @DisplayName("a stale transition never resurrects a completed document")
    void staleTransitionIsIgnored() {
        UUID documentId = createDocument();
        documentService.claimForProcessing(documentId);

        assertThat(documentService.completeProcessing(documentId,
                new DocumentService.ProcessedDocument("some text", 2, 9, null, null, null, null)))
                .isTrue();

        // Progress update after completion must be rejected, not reopen the document.
        assertThat(documentService.updateProcessingStatus(
                documentId, DocumentStatus.PROCESSING, 30f, null)).isFalse();
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.COMPLETED);
    }

}
