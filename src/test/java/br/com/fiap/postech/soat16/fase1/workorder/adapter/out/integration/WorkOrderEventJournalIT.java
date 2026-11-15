package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class WorkOrderEventJournalIT {
    @Inject
    WorkOrderEventJournal journal;
    @Inject
    ObjectMapper mapper;
    @Inject
    EstimateResultJournal resultJournal;

    @Test
    void recordsOpeningAndProjectionTogether() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = request(true);
        await(() -> Panache.withTransaction(() -> journal.recordOpening(orderId, request)));
        Object[] row = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT CAST(o.payload AS text), p.status
                        FROM oficina_mecanica.work_order_outbox o
                        JOIN oficina_mecanica.work_order_estimate_projection p ON p.opening_event_id = o.event_id
                        WHERE o.work_order_id = :id
                        """, Object[].class).setParameter("id", orderId).getSingleResult())));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.readTree(
                Path.of("docs/events/v1/work-order-opened.schema.json").toFile()));
        var errors = schema.validate(mapper.readTree((String) row[0]));
        assertTrue(errors.isEmpty(), () -> errors.toString() + " payload=" + row[0]);
        assertEquals("AGUARDANDO", row[1]);
    }

    @Test
    void rollsBackBothRecordsOnProjectionConflict() {
        UUID orderId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> await(() -> Panache.withTransaction(() ->
                journal.recordOpening(orderId, request(false))
                        .chain(() -> journal.recordOpening(orderId, request(false))))));
        Number count = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT count(*) FROM oficina_mecanica.work_order_outbox WHERE work_order_id = :id
                        """, Long.class).setParameter("id", orderId).getSingleResult())));
        assertEquals(0L, count.longValue());
    }

    @Test
    void handlesDuplicateAndConflictingResultsWithoutOverwritingProjection() {
        UUID orderId = UUID.randomUUID();
        await(() -> Panache.withTransaction(() -> journal.recordOpening(orderId, request(true))));
        Object[] ids = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT opening_event_id, saga_id, correlation_id
                        FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                        """, Object[].class).setParameter("id", orderId).getSingleResult())));
        var created = new EstimateResultJournal.Event(UUID.randomUUID(), orderId, (UUID) ids[0],
                (UUID) ids[1], (UUID) ids[2], UUID.randomUUID(), "{}", null, null);
        assertEquals(EstimateResultJournal.Outcome.APPLIED, await(() -> resultJournal.record(created)));
        assertEquals(EstimateResultJournal.Outcome.DUPLICATE, await(() -> resultJournal.record(created)));
        var conflicting = new EstimateResultJournal.Event(UUID.randomUUID(), orderId, (UUID) ids[0],
                (UUID) ids[1], (UUID) ids[2], null, null, "PART_NOT_FOUND", "Peça não encontrada");
        assertEquals(EstimateResultJournal.Outcome.IGNORED, await(() -> resultJournal.record(conflicting)));
        String status = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT status FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                        """, String.class).setParameter("id", orderId).getSingleResult())));
        assertEquals("CRIADO", status);
    }

    @Test
    void ignoresOtherCorrelationAndRecordsFailureReason() {
        UUID orderId = UUID.randomUUID();
        await(() -> Panache.withTransaction(() -> journal.recordOpening(orderId, request(true))));
        Object[] ids = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT opening_event_id, saga_id, correlation_id
                        FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                        """, Object[].class).setParameter("id", orderId).getSingleResult())));
        var wrong = new EstimateResultJournal.Event(UUID.randomUUID(), orderId, (UUID) ids[0],
                (UUID) ids[1], UUID.randomUUID(), null, null, "PART_NOT_FOUND", "Peça não encontrada");
        assertEquals(EstimateResultJournal.Outcome.IGNORED, await(() -> resultJournal.record(wrong)));
        var failure = new EstimateResultJournal.Event(UUID.randomUUID(), orderId, (UUID) ids[0],
                (UUID) ids[1], (UUID) ids[2], null, null, "PART_NOT_FOUND", "Peça não encontrada");
        assertEquals(EstimateResultJournal.Outcome.APPLIED, await(() -> resultJournal.record(failure)));
        Object[] result = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("""
                        SELECT status, failure_code, failure_message
                        FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                        """, Object[].class).setParameter("id", orderId).getSingleResult())));
        assertEquals("FALHOU", result[0]);
        assertEquals("PART_NOT_FOUND", result[1]);
        assertEquals("Peça não encontrada", result[2]);
    }

    @Test
    void missingProjectionRollsBackInboxForRetry() {
        var event = new EstimateResultJournal.Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, "PART_NOT_FOUND", "Peça não encontrada");
        assertThrows(IllegalStateException.class, () -> await(() -> resultJournal.record(event)));
        Number count = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("SELECT count(*) FROM oficina_mecanica.work_order_inbox WHERE event_id = :id", Long.class)
                .setParameter("id", event.eventId()).getSingleResult())));
        assertEquals(0L, count.longValue());
    }

    private OpenWorkOrderCommand request(boolean hasItems) {
        return new OpenWorkOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "Teste", WorkOrderPriority.LOW,
                null, hasItems ? List.of(new OpenWorkOrderCommand.RequestedService(UUID.randomUUID())) : List.of(),
                List.of());
    }

    private <T> T await(Supplier<Uni<T>> action) {
        try {
            return VertxContextSupport.subscribeAndAwait(action);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
