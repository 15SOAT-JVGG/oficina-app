package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WorkOrderFailureMetricsFilterTest {

    @Test
    void countsOnlyServerFailuresOnWorkOrderRoutes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkOrderFailureMetricsFilter filter = new WorkOrderFailureMetricsFilter(registry);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("v1/work-orders");
        when(response.getStatus()).thenReturn(422);
        filter.filter(request, response);
        assertEquals(0, registry.get("workshop.work.order.processing.failures").counter().count());
        when(response.getStatus()).thenReturn(500);
        filter.filter(request, response);
        assertEquals(1, registry.get("workshop.work.order.processing.failures").counter().count());
        when(uri.getPath()).thenReturn("v1/auth/login");
        filter.filter(request, response);
        assertEquals(1, registry.get("workshop.work.order.processing.failures").counter().count());
    }
}
