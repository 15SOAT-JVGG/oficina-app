package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateProgressPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderEventJournalPort;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration.EstimateProjection;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class WorkOrderEventJournal implements WorkOrderEventJournalPort, EstimateProgressPort {
    private final ObjectMapper objectMapper;

    @Override
    public Uni<Void> recordOpening(UUID workOrderId, OpenWorkOrderCommand request) {
        UUID eventId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String payload = openingPayload(workOrderId, request, eventId, sagaId, correlationId);
        String traceparent = currentTraceparent();
        return Panache.getSession().flatMap(session -> session.createNativeQuery("""
                INSERT INTO oficina_mecanica.work_order_outbox
                    (event_id, work_order_id, event_type, payload, traceparent)
                VALUES (:eventId, :workOrderId, 'work-order.opened', CAST(CAST(:payload AS text) AS jsonb), :traceparent)
                """)
                .setParameter("eventId", eventId)
                .setParameter("workOrderId", workOrderId)
                .setParameter("payload", payload)
                .setParameter("traceparent", traceparent)
                .executeUpdate()
                .flatMap(ignored -> session.createNativeQuery("""
                        INSERT INTO oficina_mecanica.work_order_estimate_projection
                            (work_order_id, opening_event_id, saga_id, correlation_id, status)
                        VALUES (:workOrderId, :eventId, :sagaId, :correlationId, :status)
                        """)
                        .setParameter("workOrderId", workOrderId)
                        .setParameter("eventId", eventId)
                        .setParameter("sagaId", sagaId)
                        .setParameter("correlationId", correlationId)
                        .setParameter("status", request.hasInitialRequest() ? "AGUARDANDO" : null)
                        .executeUpdate()))
                .replaceWithVoid();
    }

    @Override
    public Uni<Progress> findProgress(UUID workOrderId) {
        return Panache.getSession().flatMap(session -> session.createNativeQuery("""
                SELECT status, failure_message FROM oficina_mecanica.work_order_estimate_projection
                WHERE work_order_id = :id
                """, Object[].class).setParameter("id", workOrderId).getResultList())
                .map(rows -> {
                    if (rows.isEmpty() || rows.getFirst()[0] == null) {
                        return null;
                    }
                    Object[] row = rows.getFirst();
                    return new Progress(EstimateProjection.Status.valueOf((String) row[0]), (String) row[1]);
                });
    }

    private String openingPayload(UUID workOrderId, OpenWorkOrderCommand request, UUID eventId,
            UUID sagaId, UUID correlationId) {
        var envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", "work-order.opened");
        envelope.put("version", 1);
        envelope.put("sagaId", sagaId.toString());
        envelope.put("correlationId", correlationId.toString());
        envelope.putNull("causationId");
        envelope.put("workOrderId", workOrderId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.set("data", objectMapper.valueToTree(Map.of("services", request.services(), "parts", request.parts())));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Não foi possível serializar a abertura da OS", exception);
        }
    }

    private static String currentTraceparent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return null;
        }
        return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex();
    }
}
