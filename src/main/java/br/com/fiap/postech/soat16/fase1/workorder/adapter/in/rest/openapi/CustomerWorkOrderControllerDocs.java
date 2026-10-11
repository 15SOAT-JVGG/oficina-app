package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.openapi;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableRequestDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;

import io.smallrye.mutiny.Uni;

@Path("/v1/me/work-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Customer — My Work Orders",
        description = "Rotas do cliente autenticado pelo CPF. O token é emitido pela função de autenticação "
                + "(POST /v1/auth/customer no API Gateway) e identifica o cliente pelo claim sub.")
public interface CustomerWorkOrderControllerDocs {

    @GET
    @Operation(summary = "Listar as minhas ordens de serviço",
            description = "Ordens do cliente autenticado, da mais recente para a mais antiga.")
    @APIResponse(responseCode = "200", description = "Ordens do cliente",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = PageableResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Parâmetros de paginação inválidos")
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado")
    @APIResponse(responseCode = "403", description = "Token não pertence a um cliente")
    Uni<PageableResponseDto<WorkOrderResponseDto>> findMyWorkOrders(
            @BeanParam @Valid PageableRequestDto pageable);

    @GET
    @Path("/{id}")
    @Operation(summary = "Detalhar uma das minhas ordens de serviço")
    @APIResponse(responseCode = "200", description = "Ordem de serviço do cliente",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WorkOrderResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado")
    @APIResponse(responseCode = "403", description = "Token não pertence a um cliente")
    @APIResponse(responseCode = "404", description = "Ordem inexistente ou de outro cliente")
    Uni<WorkOrderResponseDto> findMyWorkOrder(
            @Parameter(name = "id", description = "Work order identifier", required = true, in = ParameterIn.PATH)
            @PathParam("id") UUID id);

    @PATCH
    @Path("/{id}/estimate/{estimateId}/approve")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Aprovar o orçamento de uma das minhas ordens",
            description = "Mantém a reserva de estoque e leva a ordem a IN_PROGRESS.")
    @APIResponse(responseCode = "200", description = "Orçamento aprovado",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = EstimateResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado")
    @APIResponse(responseCode = "403", description = "Token não pertence a um cliente")
    @APIResponse(responseCode = "404", description = "Ordem ou orçamento inexistente, ou de outro cliente")
    @APIResponse(responseCode = "409", description = "Orçamento já aprovado ou recusado")
    @APIResponse(responseCode = "422", description = "Ordem de serviço bloqueada para novas alterações")
    Uni<EstimateResponseDto> approveMyEstimate(
            @Parameter(name = "id", description = "Work order identifier", required = true, in = ParameterIn.PATH)
            @PathParam("id") UUID id,
            @Parameter(name = "estimateId", description = "Estimate identifier", required = true,
                    in = ParameterIn.PATH)
            @PathParam("estimateId") UUID estimateId);

    @PATCH
    @Path("/{id}/estimate/{estimateId}/reject")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Recusar o orçamento de uma das minhas ordens",
            description = "Devolve as peças ao estoque, conclui a ordem e preenche cancelledAt.")
    @APIResponse(responseCode = "200", description = "Orçamento recusado",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = EstimateResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado")
    @APIResponse(responseCode = "403", description = "Token não pertence a um cliente")
    @APIResponse(responseCode = "404", description = "Ordem ou orçamento inexistente, ou de outro cliente")
    @APIResponse(responseCode = "409", description = "Orçamento já aprovado ou recusado")
    @APIResponse(responseCode = "422", description = "Ordem de serviço bloqueada para novas alterações")
    Uni<EstimateResponseDto> rejectMyEstimate(
            @Parameter(name = "id", description = "Work order identifier", required = true, in = ParameterIn.PATH)
            @PathParam("id") UUID id,
            @Parameter(name = "estimateId", description = "Estimate identifier", required = true,
                    in = ParameterIn.PATH)
            @PathParam("estimateId") UUID estimateId);
}
