package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.stock;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.exception.LegacyFlowUnavailable;

import io.smallrye.mutiny.Uni;

@ApplicationScoped
@RolesAllowed({"ADMIN", "MECHANIC"})
public class LegacyStockController implements LegacyStockControllerDocs {
    @Override
    public Uni<Response> findLowStock() {
        return LegacyFlowUnavailable.response();
    }

    @Override
    @RolesAllowed("ADMIN")
    public Uni<Response> adjustStock(UUID id, int adjustment) {
        return LegacyFlowUnavailable.response();
    }
}
