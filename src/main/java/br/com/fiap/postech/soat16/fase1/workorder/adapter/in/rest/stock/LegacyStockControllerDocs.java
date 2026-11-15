package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.stock;

import java.util.UUID;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.smallrye.mutiny.Uni;

@Path("/admin/parts")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Estoque", description = "Operações legadas indisponíveis durante a integração")
public interface LegacyStockControllerDocs {
    @GET
    @Path("/low-stock")
    @Operation(summary = "Consultar estoque baixo", description = "Indisponível enquanto o estoque migra para Execution.")
    @APIResponse(responseCode = "503", description = "Fluxo de estoque temporariamente indisponível")
    Uni<Response> findLowStock();

    @PATCH
    @Path("/{id}/stock")
    @Operation(summary = "Ajustar estoque", description = "Indisponível enquanto o estoque migra para Execution.")
    @APIResponse(responseCode = "503", description = "Fluxo de estoque temporariamente indisponível")
    Uni<Response> adjustStock(@PathParam("id") UUID id, @QueryParam("adjustment") int adjustment);
}
