package br.com.fiap.postech.soat16.fase1.shared.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

class HttpServerMetricsConfigurationTest {

    @Test
    @DisplayName("publica buckets de latência limitados para requisições HTTP")
    void configuresHttpServerLatencyBuckets() {
        var filter = new HttpServerMetricsConfiguration().httpServerLatencyBuckets();
        var registry = new SimpleMeterRegistry();
        Meter.Id httpRequest = registry.timer("http.server.requests").getId();
        DistributionStatisticConfig configured = filter.configure(httpRequest, DistributionStatisticConfig.DEFAULT);

        assertArrayEquals(
                new double[] {50_000_000, 100_000_000, 250_000_000, 500_000_000, 750_000_000,
                    1_000_000_000, 1_500_000_000, 2_000_000_000, 3_000_000_000L, 5_000_000_000L},
                configured.getServiceLevelObjectiveBoundaries());
    }

    @Test
    @DisplayName("não altera a distribuição de métricas que não são HTTP")
    void leavesOtherMetersUnchanged() {
        var filter = new HttpServerMetricsConfiguration().httpServerLatencyBuckets();
        var registry = new SimpleMeterRegistry();
        Meter.Id other = registry.counter("business.events").getId();
        DistributionStatisticConfig config = DistributionStatisticConfig.DEFAULT;

        assertSame(config, filter.configure(other, config));
    }
    @Test
    @DisplayName("exporta contagem e buckets Prometheus em segundos após uma requisição")
    void exportsSecondsBucketsAndRequestCount() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().meterFilter(new HttpServerMetricsConfiguration().httpServerLatencyBuckets());
            Timer.builder("http.server.requests").tags("method", "GET", "status", "200", "uri", "/v1/demo")
                    .register(registry).record(100, TimeUnit.MILLISECONDS);
            String scrape = registry.scrape();
            assertTrue(scrape.contains("http_server_requests_seconds_count"));
            assertTrue(scrape.lines().anyMatch(line -> line.contains("le=\"0.1\"") && line.endsWith(" 1.0")));
            assertTrue(scrape.lines().anyMatch(line -> line.contains("le=\"0.05\"") && line.endsWith(" 0.0")));
        } finally {
            registry.close();
        }
    }

}
