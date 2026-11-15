package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/** Inbox e atualização condicional compartilham a mesma transação PostgreSQL. */
@ApplicationScoped
public class EstimateResultJournal {
    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    public record Event(UUID eventId, UUID workOrderId, UUID openingEventId, UUID sagaId,
            UUID correlationId, UUID estimateId, String estimateData, String failureCode, String failureMessage) {
        public Event {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(workOrderId);
            Objects.requireNonNull(openingEventId);
            Objects.requireNonNull(sagaId);
            Objects.requireNonNull(correlationId);
            boolean created = estimateId != null && estimateData != null && !estimateData.isBlank()
                    && failureCode == null && failureMessage == null;
            boolean failed = estimateId == null && estimateData == null && failureCode != null && !failureCode.isBlank()
                    && failureMessage != null && !failureMessage.isBlank();
            if (!created && !failed) {
                throw new IllegalArgumentException("Resultado de orçamento inválido");
            }
        }

        boolean isCreated() {
            return estimateId != null;
        }
    }

    /** Retorna apenas depois do commit; erros mantêm a mensagem apta a ser reentregue. */
    public Uni<Outcome> record(Event event) {
        return Panache.withTransaction(() -> insertAndApply(event));
    }

    private Uni<Outcome> insertAndApply(Event event) {
        return Panache.getSession().flatMap(session -> session.createNativeQuery("""
                INSERT INTO oficina_mecanica.work_order_inbox(event_id, work_order_id, event_type)
                VALUES (:eventId, :workOrderId, :eventType) ON CONFLICT (event_id) DO NOTHING
                """)
                .setParameter("eventId", event.eventId())
                .setParameter("workOrderId", event.workOrderId())
                .setParameter("eventType", event.isCreated() ? "estimate.created" : "estimate.creation-failed")
                .executeUpdate()
                .flatMap(inserted -> {
                    if (inserted == 0) {
                        return Uni.createFrom().item(Outcome.DUPLICATE);
                    }
                    String update = event.isCreated()
                            ? "status = 'CRIADO', estimate_id = :estimateId, "
                                    + "estimate_data = CAST(CAST(:estimateData AS text) AS jsonb)"
                            : "status = 'FALHOU', failure_code = :failureCode, failure_message = :failureMessage";
                    var query = session.createNativeQuery("UPDATE oficina_mecanica.work_order_estimate_projection SET "
                            + update + " WHERE work_order_id = :workOrderId AND opening_event_id = :openingEventId"
                            + " AND saga_id = :sagaId AND correlation_id = :correlationId AND status = 'AGUARDANDO'");
                    query.setParameter("workOrderId", event.workOrderId())
                            .setParameter("openingEventId", event.openingEventId())
                            .setParameter("sagaId", event.sagaId())
                            .setParameter("correlationId", event.correlationId());
                    if (event.isCreated()) {
                        query.setParameter("estimateId", event.estimateId()).setParameter("estimateData", event.estimateData());
                    } else {
                        query.setParameter("failureCode", event.failureCode()).setParameter("failureMessage", event.failureMessage());
                    }
                    return query.executeUpdate().flatMap(updated -> {
                        if (updated == 1) {
                            return Uni.createFrom().item(Outcome.APPLIED);
                        }
                        return session.createNativeQuery("""
                                SELECT count(*) FROM oficina_mecanica.work_order_estimate_projection
                                WHERE work_order_id = :workOrderId
                                """, Long.class).setParameter("workOrderId", event.workOrderId()).getSingleResult()
                                .map(count -> {
                                    if (count == 0) {
                                        throw new IllegalStateException("Projeção da OS ainda não existe");
                                    }
                                    return Outcome.IGNORED;
                                });
                    });
                }));
    }
}
