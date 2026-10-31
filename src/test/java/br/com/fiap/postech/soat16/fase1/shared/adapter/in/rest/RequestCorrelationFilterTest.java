package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RequestCorrelationFilter")
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    @DisplayName("preserves a safe incoming request id in the response")
    void shouldPreserveSafeRequestId() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        ContainerResponseContext response = responseWithHeaders();
        wireRequestProperties(request);
        when(request.getHeaderString("X-Request-ID")).thenReturn("client-request-42");

        filter.filter(request);
        assertEquals("client-request-42", MDC.get("requestId"));
        filter.filter(request, response);

        assertEquals("client-request-42", response.getHeaders().getFirst("X-Request-ID"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    @DisplayName("replaces an unsafe incoming request id with a generated identifier")
    void shouldReplaceUnsafeRequestId() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        ContainerResponseContext response = responseWithHeaders();
        wireRequestProperties(request);
        when(request.getHeaderString("X-Request-ID")).thenReturn("token=do-not-echo");

        filter.filter(request);
        String loggedRequestId = (String) MDC.get("requestId");
        filter.filter(request, response);

        String responseId = (String) response.getHeaders().getFirst("X-Request-ID");
        assertNotNull(responseId);
        assertTrue(responseId.matches("[0-9a-f-]{36}"));
        assertEquals(responseId, loggedRequestId);
        assertNull(MDC.get("requestId"));
    }

    private static void wireRequestProperties(ContainerRequestContext request) {
        Map<String, Object> properties = new HashMap<>();
        when(request.getProperty(anyString())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            properties.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setProperty(anyString(), org.mockito.ArgumentMatchers.any());
    }

    private static ContainerResponseContext responseWithHeaders() {
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        return response;
    }
}
