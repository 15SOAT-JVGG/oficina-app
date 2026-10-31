package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Counts server failures only; expected 4xx validation/RBAC responses are not processing failures. */
@Provider
public class WorkOrderFailureMetricsFilter implements ContainerResponseFilter {

    private final Counter failures;

    public WorkOrderFailureMetricsFilter(MeterRegistry registry) {
        failures = registry.counter("workshop.work.order.processing.failures");
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() >= 500 && request.getUriInfo().getPath().contains("work-orders")) {
            failures.increment();
        }
    }
}
