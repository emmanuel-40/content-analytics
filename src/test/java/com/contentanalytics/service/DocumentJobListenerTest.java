package com.contentanalytics.service;

import com.contentanalytics.service.DocumentProcessingService.JobOutcome;
import com.contentanalytics.service.JobQueue.DocumentJob;
import com.contentanalytics.service.RabbitMqJobQueue.DocumentJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentJobListener manual acknowledgement")
class DocumentJobListenerTest {

    @Mock
    private DocumentProcessingService documentProcessingService;

    @Mock
    private Channel channel;

    @InjectMocks
    private DocumentJobListener listener;

    private Message message(DocumentJob job) throws Exception {
        byte[] body = new ObjectMapper().writeValueAsBytes(DocumentJobMessage.from(job));
        MessageProperties properties = new MessageProperties();
        properties.setHeader(RabbitMqJobQueue.JOB_ID_HEADER, job.jobId().toString());
        properties.setHeader(RabbitMqJobQueue.ATTEMPT_HEADER, job.attempt());
        return new Message(body, properties);
    }

    @Test
    @DisplayName("a completed job is ACKed once from the stable job contract")
    void completedJobIsAcked() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(documentProcessingService.processJob(any(DocumentJob.class)))
                .thenReturn(JobOutcome.COMPLETED);

        listener.onDocumentJob(message(new DocumentJob(jobId, documentId, userId, 2)), channel, 7L);

        verify(documentProcessingService).processJob(any(DocumentJob.class));
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("an exhausted job is NACKed without requeue so Rabbit dead-letters it")
    void failedJobIsDeadLettered() throws Exception {
        when(documentProcessingService.processJob(any(DocumentJob.class)))
                .thenReturn(JobOutcome.FAILED);

        listener.onDocumentJob(message(DocumentJob.of(UUID.randomUUID(), UUID.randomUUID())), channel, 9L);

        verify(channel).basicNack(9L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("headers take precedence over the payload for the stable job id")
    void headersDriveStableJobId() throws Exception {
        UUID headerJobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.processJob(any(DocumentJob.class)))
                .thenReturn(JobOutcome.COMPLETED);

        Message message = message(DocumentJob.of(documentId, UUID.randomUUID()));
        message.getMessageProperties().setHeader(RabbitMqJobQueue.JOB_ID_HEADER, headerJobId.toString());
        listener.onDocumentJob(message, channel, 1L);

        org.mockito.ArgumentCaptor<DocumentJob> captor =
                org.mockito.ArgumentCaptor.forClass(DocumentJob.class);
        verify(documentProcessingService).processJob(captor.capture());
        assertThat(captor.getValue().jobId()).isEqualTo(headerJobId);
        assertThat(captor.getValue().documentId()).isEqualTo(documentId);
    }

}
