package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.MDC;

import io.opentelemetry.api.trace.Span;

/**
 * Adds bounded request correlation to response headers, JSON log MDC and the active trace.
 * Quarkus keeps MDC in its duplicated reactive context, so Mutiny continuations retain the
 * request value without manual ThreadLocal propagation.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
public class RequestCorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_PROPERTY = RequestCorrelationFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String requestId = validRequestId(requestContext.getHeaderString(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
        MDC.put("requestId", requestId);
        Span.current().setAttribute("request.id", requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object requestId = requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId instanceof String value) {
            responseContext.getHeaders().putSingle(REQUEST_ID_HEADER, value);
        }
        MDC.remove("requestId");
    }

    private static String validRequestId(String requestId) {
        return requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches() ? requestId : null;
    }

}
