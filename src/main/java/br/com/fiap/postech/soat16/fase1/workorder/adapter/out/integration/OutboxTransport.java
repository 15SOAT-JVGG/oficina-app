package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.util.UUID;

import io.smallrye.mutiny.Uni;

public interface OutboxTransport {
    record Message(UUID eventId, UUID workOrderId, String type, String payload, String traceparent) { }

    boolean isConfigured();

    Uni<Void> publish(Message message);
}
