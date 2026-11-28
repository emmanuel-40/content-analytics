package com.contentanalytics.service;

import com.contentanalytics.dto.DocumentUploadRequestDto;
import com.contentanalytics.entity.User;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentUploadTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private JobQueue jobQueue;

    @Mock
    private MultipartFile file;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(documentRepository, userRepository, storageService, jobQueue);
        ReflectionTestUtils.setField(service, "maxUploadSize", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "allowedFileTypes", "pdf,txt,docx,pptx");
    }

    @Test
    void deletesStoredFileWhenDocumentInsertFails() {
        UUID userId = UUID.randomUUID();
        String fileKey = userId + "/stored.docx";
        String officeMimeType =
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("report.docx");
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn(officeMimeType);
        when(storageService.saveFile(file, userId)).thenReturn(fileKey);
        when(documentRepository.save(any(com.contentanalytics.entity.Document.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed"));

        DocumentUploadRequestDto request = new DocumentUploadRequestDto(file, null);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.uploadDocument(userId, request));

        verify(storageService).deleteFile(fileKey);
    }
}
