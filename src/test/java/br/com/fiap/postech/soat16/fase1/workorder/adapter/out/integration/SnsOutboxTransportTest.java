package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

class SnsOutboxTransportTest {
    @Test
    void publishesFifoIdentifiersAndTraceAttribute() {
        var transport = new SnsOutboxTransport();
        transport.topicArn = Optional.of("arn:aws:sns:us-east-1:000000000000:os.fifo");
        transport.client = mock(SnsAsyncClient.class);
        when(transport.client.publish(any(PublishRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PublishResponse.builder().build()));
        UUID event = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        String trace = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        assertTrue(transport.isConfigured());
        transport.publish(new OutboxTransport.Message(event, order, "work-order.opened", "{}", trace))
                .await().indefinitely();
        var request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(transport.client).publish(request.capture());
        assertEquals(order.toString(), request.getValue().messageGroupId());
        assertEquals(event.toString(), request.getValue().messageDeduplicationId());
        assertEquals(trace, request.getValue().messageAttributes().get("traceparent").stringValue());
        assertEquals("work-order.opened", request.getValue().messageAttributes().get("type").stringValue());
        transport.close();
        verify(transport.client).close();
    }

    @Test
    void skipsMissingTraceAndDetectsAbsentTopic() {
        var transport = new SnsOutboxTransport();
        transport.topicArn = Optional.empty();
        assertFalse(transport.isConfigured());
        transport.topicArn = Optional.of("");
        assertFalse(transport.isConfigured());
        transport.topicArn = Optional.of("arn:aws:sns:us-east-1:000000000000:os.fifo");
        transport.client = mock(SnsAsyncClient.class);
        when(transport.client.publish(any(PublishRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PublishResponse.builder().build()));
        transport.publish(new OutboxTransport.Message(UUID.randomUUID(), UUID.randomUUID(), "type", "{}", null))
                .await().indefinitely();
        var request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(transport.client).publish(request.capture());
        assertFalse(request.getValue().messageAttributes().containsKey("traceparent"));
    }
}
