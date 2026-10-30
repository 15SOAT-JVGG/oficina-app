package br.com.fiap.postech.soat16.fase1.shared.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HttpServerMetricsConfigurationTest {

    @Test
    @DisplayName("publica buckets de latência limitados para requisições HTTP")
    void configuresHttpServerLatencyBuckets() {
        var filter = new HttpServerMetricsConfiguration().httpServerLatencyBuckets();
        var registry = new SimpleMeterRegistry();
        Meter.Id httpRequest = registry.timer("http.server.requests").getId();
        DistributionStatisticConfig configured = filter.configure(httpRequest, DistributionStatisticConfig.DEFAULT);

        assertArrayEquals(
                new double[] {0.05, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0},
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
}
