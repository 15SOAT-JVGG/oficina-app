package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.exception;

import java.util.Map;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

/** Resposta temporária para operações que aguardam integração entre serviços. */
public final class LegacyFlowUnavailable {
    private LegacyFlowUnavailable() { }

    public static <T> Uni<T> response() {
        var response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("status", 503, "code", "LEGACY_FLOW_UNAVAILABLE",
                        "message", "Fluxo indisponível durante a integração de orçamento e estoque"))
                .build();
        return Uni.createFrom().failure(new WebApplicationException(response));
    }
}
