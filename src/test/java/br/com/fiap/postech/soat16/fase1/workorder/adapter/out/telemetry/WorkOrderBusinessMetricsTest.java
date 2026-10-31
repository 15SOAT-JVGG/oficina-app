package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Query;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;

class WorkOrderBusinessMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsDatabaseFailureWithoutInventingBusinessValues() {
        PgPool pool = mock(PgPool.class);
        Query<RowSet<Row>> query = mock(Query.class);
        when(pool.query(WorkOrderBusinessMetrics.SQL)).thenReturn(query);
        when(query.execute()).thenReturn(Uni.createFrom().failure(new IllegalStateException("unavailable")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkOrderBusinessMetrics metrics = new WorkOrderBusinessMetrics(pool, registry);
        assertThrows(IllegalStateException.class, () -> metrics.refresh().await().indefinitely());
        assertTrue(Double.isNaN(registry.get("workshop.work.orders.opened.today").gauge().value()));
        assertEquals(1, registry.get("workshop.integration.calls").tag("outcome", "failure").counter().count());
    }
}
