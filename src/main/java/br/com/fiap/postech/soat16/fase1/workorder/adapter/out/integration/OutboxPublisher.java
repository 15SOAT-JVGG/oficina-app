package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxTransport transport;

    @Scheduled(every = "${app.events.outbox.interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> scheduledPublish() {
        if (!transport.isConfigured()) {
            return Uni.createFrom().voidItem();
        }
        return publishNext().onFailure().invoke(error -> Log.error("Falha ao publicar outbox; nova tentativa agendada", error))
                .onFailure().recoverWithItem(false).replaceWithVoid();
    }

    public Uni<Boolean> publishNext() {
        return Panache.withTransaction(() -> Panache.getSession().flatMap(session -> session.createNativeQuery("""
                SELECT o.sequence_id, o.event_id, o.work_order_id, o.event_type, CAST(o.payload AS text), o.traceparent
                FROM oficina_mecanica.work_order_outbox o
                WHERE o.published_at IS NULL AND NOT EXISTS (
                    SELECT 1 FROM oficina_mecanica.work_order_outbox earlier
                    WHERE earlier.work_order_id = o.work_order_id AND earlier.published_at IS NULL
                      AND earlier.sequence_id < o.sequence_id
                )
                ORDER BY o.sequence_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, Object[].class).getResultList().flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Uni.createFrom().item(false);
                    }
                    Object[] row = rows.getFirst();
                    var message = new OutboxTransport.Message((UUID) row[1], (UUID) row[2],
                            (String) row[3], (String) row[4], (String) row[5]);
                    var context = Vertx.currentContext();
                    return transport.publish(message).emitOn(task -> context.runOnContext(ignored -> task.run()))
                            .chain(() -> session.createNativeQuery("""
                            UPDATE oficina_mecanica.work_order_outbox SET published_at = now() WHERE sequence_id = :id
                            """).setParameter("id", row[0]).executeUpdate()).replaceWith(true);
                })));
    }
}
