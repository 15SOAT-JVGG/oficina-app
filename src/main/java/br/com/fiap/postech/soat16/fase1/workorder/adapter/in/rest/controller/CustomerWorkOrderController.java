package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.jwt.JsonWebToken;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableRequestDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.WorkOrderRestMapper;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.openapi.CustomerWorkOrderControllerDocs;
import br.com.fiap.postech.soat16.fase1.workorder.application.WorkOrderService;

import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

/**
 * O canal do cliente autenticado pelo CPF. O cliente nunca informa quem é: a identidade sai do
 * {@code sub} do token emitido pela função de autenticação, que carrega o id do cliente.
 */
@ApplicationScoped
@RequiredArgsConstructor
@Path("/v1/me/work-orders")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("CUSTOMER")
public class CustomerWorkOrderController implements CustomerWorkOrderControllerDocs {

    private static final String NOT_A_CUSTOMER_TOKEN = "Token does not identify a customer";

    private final WorkOrderService service;
    private final JsonWebToken token;

    @GET
    @Override
    public Uni<PageableResponseDto<WorkOrderResponseDto>> findMyWorkOrders(
            @BeanParam @Valid PageableRequestDto pageable) {
        return service.findCustomerWorkOrders(authenticatedCustomerId(), pageable.getPage(), pageable.getSize())
                .map(WorkOrderRestMapper::toResponse);
    }

    @GET
    @Path("/{id}")
    @Override
    public Uni<WorkOrderResponseDto> findMyWorkOrder(@PathParam("id") UUID id) {
        return service.findCustomerWorkOrder(authenticatedCustomerId(), id)
                .map(WorkOrderRestMapper::toResponse);
    }

    @PATCH
    @Path("/{id}/estimate/{estimateId}/approve")
    @Consumes(MediaType.WILDCARD)
    @Override
    public Uni<EstimateResponseDto> approveMyEstimate(@PathParam("id") UUID id,
                                                       @PathParam("estimateId") UUID estimateId) {
        return service.approveCustomerEstimate(authenticatedCustomerId(), id, estimateId)
                .map(WorkOrderRestMapper::toResponse);
    }

    @PATCH
    @Path("/{id}/estimate/{estimateId}/reject")
    @Consumes(MediaType.WILDCARD)
    @Override
    public Uni<EstimateResponseDto> rejectMyEstimate(@PathParam("id") UUID id,
                                                      @PathParam("estimateId") UUID estimateId) {
        return service.rejectCustomerEstimate(authenticatedCustomerId(), id, estimateId)
                .map(WorkOrderRestMapper::toResponse);
    }

    private UUID authenticatedCustomerId() {
        String subject = token.getSubject();
        if (subject == null) {
            throw new ForbiddenException(NOT_A_CUSTOMER_TOKEN);
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new ForbiddenException(NOT_A_CUSTOMER_TOKEN, exception);
        }
    }
}
