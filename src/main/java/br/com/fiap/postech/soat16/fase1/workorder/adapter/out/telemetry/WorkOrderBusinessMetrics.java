package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;

/** Persistent aggregates: replicas expose the same values, so dashboards use max, never sum. */
@ApplicationScoped
public class WorkOrderBusinessMetrics {

    static final String SQL = """
            with transitions as (
                select h.new_status, h.changed_at,
                    lead(h.changed_at) over (
                        partition by h.work_order_id order by h.changed_at, h.work_order_history_id
                    ) as next_at
                from oficina_mecanica.work_order_history h
                join oficina_mecanica.work_order w using (work_order_id)
                where w.cancelled_at is null
            )
            select 'daily' as metric, count(*)::double precision as value
            from oficina_mecanica.work_order where opened_at >= current_date
            union all
            select new_status, avg(extract(epoch from (next_at - changed_at)))::double precision
            from transitions where next_at is not null
                and new_status in ('DIAGNOSIS', 'IN_PROGRESS', 'COMPLETED')
            group by new_status
            """;

    private final PgPool pool;
    private final Counter successes;
    private final Counter failures;
    private final Map<String, Double> values = new ConcurrentHashMap<>();

    public WorkOrderBusinessMetrics(PgPool pool, MeterRegistry registry) {
        this.pool = pool;
        successes = registry.counter("workshop.integration.calls", "integration", "business_metrics_database", "outcome", "success");
        failures = registry.counter("workshop.integration.calls", "integration", "business_metrics_database", "outcome", "failure");
        Gauge.builder("workshop.work.orders.opened.today", values, data -> data.getOrDefault("daily", Double.NaN))
                .description("Orders opened since midnight in the database timezone")
                .register(registry);
        for (String stage : new String[]{"DIAGNOSIS", "IN_PROGRESS", "COMPLETED"}) {
            Gauge.builder("workshop.work.order.stage.duration.seconds", values,
                    data -> data.getOrDefault(stage, Double.NaN))
                    .tag("stage", stage)
                    .description("Mean completed stage duration; cancelled orders excluded")
                    .register(registry);
        }
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> refresh() {
        return pool.query(SQL).execute().invoke(rows -> {
            values.clear();
            rows.forEach(row -> values.put(row.getString("metric"), row.getDouble("value")));
            successes.increment();
        }).onFailure().invoke(failure -> failures.increment()).replaceWithVoid();
    }
}
