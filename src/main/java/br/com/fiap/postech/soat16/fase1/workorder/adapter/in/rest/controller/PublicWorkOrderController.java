package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.exception.LegacyFlowUnavailable;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.WorkOrderRestMapper;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderTrackingResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.openapi.PublicWorkOrderControllerDocs;
import br.com.fiap.postech.soat16.fase1.workorder.application.WorkOrderService;

import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
@Path("/v1/public/work-orders")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicWorkOrderController implements PublicWorkOrderControllerDocs {

    private final WorkOrderService service;

    /**
     * O link de acompanhamento é a única credencial do cliente: não existe rota por id, porque ela
     * entregaria o andamento a quem apenas conhecesse o identificador da ordem.
     */
    @GET
    @Path("/tracking/{token}")
    @Override
    public Uni<WorkOrderTrackingResponseDto> track(@PathParam("token") String token) {
        return service.track(token).map(WorkOrderRestMapper::toResponse);
    }

    /**
     * É um POST, e não um GET, porque o token vale uma única vez: um cliente de e-mail que
     * pré-carrega os links de uma mensagem consumiria a decisão sem que o cliente a tomasse.
     *
     * <p>A decisão inteira cabe no token, então a requisição não tem corpo — daí o {@code WILDCARD},
     * que dispensa o cliente de declarar um Content-Type só para satisfazer a rota.
     */
    @POST
    @Path("/estimate-decisions/{token}")
    @Consumes(MediaType.WILDCARD)
    @Override
    public Uni<EstimateResponseDto> decideEstimate(@PathParam("token") String token) {
        return LegacyFlowUnavailable.response();
    }
}
