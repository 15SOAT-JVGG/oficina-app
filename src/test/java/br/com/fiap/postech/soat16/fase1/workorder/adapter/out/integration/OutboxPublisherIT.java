package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class OutboxPublisherIT {
    @Inject
    OutboxPublisher publisher;
    @InjectMock
    SnsOutboxTransport transport;
    @Inject
    AgroalDataSource dataSource;

    @BeforeEach
    void clearJournal() {
        await(() -> Panache.withTransaction(() -> Panache.getSession().flatMap(session -> session
                .createNativeQuery("DELETE FROM oficina_mecanica.work_order_estimate_projection").executeUpdate()
                .chain(() -> session.createNativeQuery("DELETE FROM oficina_mecanica.work_order_outbox").executeUpdate()))));
    }

    @Test
    void retriesFailureAndPreservesOrderAndIdentity() {
        UUID order = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        addEvent(order, first);
        addEvent(order, second);
        when(transport.publish(any())).thenReturn(Uni.createFrom().failure(new IllegalStateException("broker indisponível")),
                Uni.createFrom().voidItem(), Uni.createFrom().voidItem());
        assertThrows(IllegalStateException.class, () -> await(publisher::publishNext));
        // A nova instância não mantém estado em memória: a retomada usa a outbox já confirmada.
        publisher = new OutboxPublisher(transport);
        assertTrue(await(publisher::publishNext));
        assertTrue(await(publisher::publishNext));
        assertFalse(await(publisher::publishNext));
        var messages = ArgumentCaptor.forClass(OutboxTransport.Message.class);
        verify(transport, times(3)).publish(messages.capture());
        assertEquals(first, messages.getAllValues().get(0).eventId());
        assertEquals(first, messages.getAllValues().get(1).eventId());
        assertEquals(second, messages.getAllValues().get(2).eventId());
        assertEquals(order, messages.getValue().workOrderId());
    }

    @Test
    void lockedEarlierEventPreventsLaterEventFromSameOrderBeingPublished() throws Exception {
        UUID firstOrder = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        addEvent(firstOrder, first);
        addEvent(firstOrder, second);
        addEvent(UUID.randomUUID(), other);
        when(transport.publish(any())).thenReturn(Uni.createFrom().voidItem());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("""
                    SELECT event_id FROM oficina_mecanica.work_order_outbox WHERE event_id = ? FOR UPDATE
                    """)) {
                lock.setObject(1, first);
                lock.executeQuery();
                assertTrue(await(publisher::publishNext));
                assertFalse(await(publisher::publishNext));
            }
            connection.rollback();
        }
        assertTrue(await(publisher::publishNext));
        assertTrue(await(publisher::publishNext));
        var messages = ArgumentCaptor.forClass(OutboxTransport.Message.class);
        verify(transport, times(3)).publish(messages.capture());
        assertEquals(other, messages.getAllValues().get(0).eventId());
        assertEquals(first, messages.getAllValues().get(1).eventId());
        assertEquals(second, messages.getAllValues().get(2).eventId());
    }

    private void addEvent(UUID order, UUID event) {
        await(() -> Panache.withTransaction(() -> Panache.getSession().flatMap(session -> session.createNativeQuery("""
                INSERT INTO oficina_mecanica.work_order_outbox(event_id, work_order_id, event_type, payload)
                VALUES (:event, :orderId, 'work-order.opened', '{}'::jsonb)
                """).setParameter("event", event).setParameter("orderId", order).executeUpdate())));
    }

    private <T> T await(Supplier<Uni<T>> action) {
        try {
            return VertxContextSupport.subscribeAndAwait(action);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
