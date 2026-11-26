package com.contentanalytics.service;

import com.contentanalytics.service.EmailQueue.EmailJobRef;
import com.contentanalytics.service.RabbitMqEmailQueue.EmailJobMessage;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailJobListener manual acknowledgement")
class EmailJobListenerTest {

    @Mock private EmailDeliveryService emailDeliveryService;
    @Mock private Channel channel;

    @InjectMocks private EmailJobListener listener;

    private Message message(UUID jobId, String dedupeKey) throws Exception {
        byte[] body = new ObjectMapper().writeValueAsBytes(new EmailJobMessage(jobId, dedupeKey));
        MessageProperties properties = new MessageProperties();
        properties.setHeader(RabbitMqEmailQueue.JOB_ID_HEADER, jobId.toString());
        properties.setHeader(RabbitMqEmailQueue.DEDUPE_KEY_HEADER, dedupeKey);
        return new Message(body, properties);
    }

    @Test
    @DisplayName("a delivered email is ACKed exactly once")
    void deliveredIsAcked() throws Exception {
        UUID jobId = UUID.randomUUID();
        listener.onEmailJob(message(jobId, "EMAIL_VERIFICATION:abc"), channel, 11L);

        verify(emailDeliveryService).deliver(new EmailJobRef(jobId, "EMAIL_VERIFICATION:abc"));
        verify(channel).basicAck(11L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("a failed delivery is dead-lettered, not requeued forever")
    void failedIsDeadLettered() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new IllegalStateException("brevo down")).when(emailDeliveryService).deliver(any());

        listener.onEmailJob(message(jobId, "EMAIL_VERIFICATION:abc"), channel, 12L);

        verify(channel).basicNack(12L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

}
