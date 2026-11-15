package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.openapi;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderTrackingResponseDto;

import io.smallrye.mutiny.Uni;

/**
 * Canal voltado ao cliente final, separado das APIs administrativas. Nada aqui é acessível por id:
 * o acompanhamento exige o token assinado que a oficina enviou por e-mail e vale trinta dias.
 * A decisão de orçamento está indisponível durante a integração.
 */
@Path("/v1/public/work-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Client — Work Orders",
        description = "Client-facing endpoints to track a work order and decide on its estimate")
public interface PublicWorkOrderControllerDocs {

    @GET
    @Path("/tracking/{token}")
    @Operation(summary = "Acompanhar a ordem de serviço pelo link recebido",
            description = "Consome o link assinado que a oficina envia por e-mail na abertura e a cada mudança de "
                    + "status. Responde apenas o andamento do atendimento e vale por trinta dias.")
    @APIResponse(responseCode = "200", description = "Andamento da ordem de serviço",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WorkOrderTrackingResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Link de acompanhamento inválido ou adulterado")
    @APIResponse(responseCode = "404", description = "Ordem de serviço não encontrada")
    @APIResponse(responseCode = "410", description = "Link de acompanhamento expirado")
    Uni<WorkOrderTrackingResponseDto> track(
            @Parameter(name = "token", description = "Signed work order tracking token",
                    required = true, in = ParameterIn.PATH)
            @PathParam("token") String token);

    @POST
    @Path("/estimate-decisions/{token}")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Decisão indisponível", description = "Aguarda integração com Billing.")
    @APIResponse(responseCode = "503", description = "Fluxo temporariamente indisponível")
    Uni<EstimateResponseDto> decideEstimate(
            @Parameter(name = "token", description = "Signed single-use estimate decision token",
                    required = true, in = ParameterIn.PATH)
            @PathParam("token") String token);
}
