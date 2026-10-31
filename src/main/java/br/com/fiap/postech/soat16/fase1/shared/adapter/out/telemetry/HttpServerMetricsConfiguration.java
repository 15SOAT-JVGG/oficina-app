package br.com.fiap.postech.soat16.fase1.shared.adapter.out.telemetry;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/** Configures bounded Prometheus latency buckets for the built-in HTTP server timer. */
@ApplicationScoped
public class HttpServerMetricsConfiguration {

    private static final String HTTP_SERVER_REQUESTS_METRIC = "http.server.requests";
    private static final double[] HTTP_LATENCY_BUCKETS_SECONDS = {
        0.05, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0
    };

    @Produces
    @Singleton
    public MeterFilter httpServerLatencyBuckets() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!HTTP_SERVER_REQUESTS_METRIC.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(Arrays.stream(HTTP_LATENCY_BUCKETS_SECONDS)
                                .map(seconds -> seconds * TimeUnit.SECONDS.toNanos(1))
                                .toArray())
                        .build()
                        .merge(config);
            }
        };
    }
}
