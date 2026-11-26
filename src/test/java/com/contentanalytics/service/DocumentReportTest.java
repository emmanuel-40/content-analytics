package com.contentanalytics.service;

import com.contentanalytics.dto.AnalysisReportDto;
import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.exception.DocumentNotFoundException;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.repository.UserRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Document analysis reports")
class DocumentReportTest {

    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        owner = saveUser("report-owner");
        stranger = saveUser("report-stranger");
    }

    private User saveUser(String prefix) {
        return userRepository.save(User.builder()
                .username(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .password("$2a$10$placeholderHashPlaceholderPlaceholderPlaceholder")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .build());
    }

    private UUID saveDocument(DocumentStatus status) {
        return documentRepository.save(Document.builder()
                .fileName("Study Notes.pdf")
                .fileKey(UUID.randomUUID() + "/study-notes.pdf")
                .fileSize(1024L)
                .mimeType("application/pdf")
                .pageCount(3)
                .status(status)
                .processingProgress(status == DocumentStatus.COMPLETED ? 100f : 0f)
                .processingCompletedAt(status == DocumentStatus.COMPLETED ? java.time.LocalDateTime.now() : null)
                .wordCount(120)
                .characterCount(900)
                .summary("A useful summary of the study notes.")
                .keyTopics("[\"study\",\"focus\"]")
                .sentiment("POSITIVE")
                .confidenceScore(0.8f)
                .user(owner)
                .build()).getId();
    }

    @Test
    @DisplayName("report is generated from saved results without touching storage")
    void reportContainsSavedAnalysis() {
        UUID documentId = saveDocument(DocumentStatus.COMPLETED);

        AnalysisReportDto report = documentService.generateAnalysisReport(documentId, owner.getId());

        assertThat(report.getFileName()).isEqualTo("Study Notes-analysis.txt");
        assertThat(report.getContentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(report.getContent())
                .contains("CONTENT ANALYTICS REPORT")
                .contains("A useful summary of the study notes.")
                .contains("study")
                .contains("focus")
                .contains("POSITIVE")
                .contains("80%")
                .contains("Words: 120")
                .contains("Characters: 900");
    }

    @Test
    @DisplayName("report requires ownership and completed analysis")
    void reportRequiresOwnershipAndCompletion() {
        UUID completedId = saveDocument(DocumentStatus.COMPLETED);
        UUID pendingId = saveDocument(DocumentStatus.UPLOADED);

        assertThatThrownBy(() -> documentService.generateAnalysisReport(completedId, stranger.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> documentService.generateAnalysisReport(pendingId, owner.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after processing completes");
    }

}
