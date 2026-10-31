package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import java.time.Duration;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.MDC;

import io.smallrye.mutiny.Uni;

/** Test-only endpoint that checks whether request MDC survives an asynchronous Uni stage. */
@ApplicationScoped
@Path("/test/observability/correlation")
@PermitAll
@Produces(MediaType.TEXT_PLAIN)
public class RequestCorrelationTestResource {

    @GET
    public Uni<String> readRequestIdAfterAsyncStage() {
        return Uni.createFrom().item(() -> (String) MDC.get("requestId"))
                .onItem().delayIt().by(Duration.ofMillis(40))
                .map(ignored -> (String) MDC.get("requestId"));
    }
}
