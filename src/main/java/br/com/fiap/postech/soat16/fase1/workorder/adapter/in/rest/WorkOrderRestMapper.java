package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest;

import java.util.List;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.InitialPartRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.InitialServiceRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderStatusUpdateRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.OpenedWorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderMetricsResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderTrackingResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.ChangeWorkOrderStatusCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.OpenWorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderMetricsResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;

public final class WorkOrderRestMapper {

    private WorkOrderRestMapper() {
    }

    public static OpenWorkOrderCommand toCommand(WorkOrderRequestDto request) {
        return new OpenWorkOrderCommand(
                request.customerId(),
                request.vehicleId(),
                request.description(),
                request.priority(),
                request.assignedWorkerId(),
                toRequestedServices(request.services()),
                toRequestedParts(request.parts()));
    }

    private static List<OpenWorkOrderCommand.RequestedService> toRequestedServices(
            List<InitialServiceRequestDto> services) {
        return services.stream()
                .map(service -> new OpenWorkOrderCommand.RequestedService(service.serviceItemId()))
                .toList();
    }

    private static List<OpenWorkOrderCommand.RequestedPart> toRequestedParts(
            List<InitialPartRequestDto> parts) {
        return parts.stream()
                .map(part -> new OpenWorkOrderCommand.RequestedPart(part.partId(), part.quantity()))
                .toList();
    }

    public static ChangeWorkOrderStatusCommand toCommand(WorkOrderStatusUpdateRequestDto request) {
        return new ChangeWorkOrderStatusCommand(request.status());
    }

    public static PageableResponseDto<WorkOrderResponseDto> toResponse(
            PagedResult<WorkOrderResult> page) {
        return PageableResponseDto.of(
                page.content().stream().map(WorkOrderRestMapper::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements());
    }

    public static OpenedWorkOrderResponseDto toResponse(OpenWorkOrderResult result) {
        return new OpenedWorkOrderResponseDto(toResponse(result.workOrder()), null);
    }

    public static WorkOrderResponseDto toResponse(WorkOrderResult result) {
        return new WorkOrderResponseDto(
                result.workOrderId(),
                result.customerId(),
                result.vehicleId(),
                result.description(),
                result.priority(),
                result.status(),
                result.openedAt(),
                result.closedAt(),
                result.cancelledAt(),
                result.estimatedValue(),
                result.finalValue(),
                result.assignedWorkerId(),
                result.estimateProgress(),
                result.estimateFailureReason());
    }

    public static WorkOrderTrackingResponseDto toResponse(WorkOrderTrackingResult result) {
        return new WorkOrderTrackingResponseDto(
                result.workOrderId(),
                result.status(),
                result.openedAt(),
                result.closedAt(),
                result.cancelledAt());
    }

    public static WorkOrderMetricsResponseDto toResponse(WorkOrderMetricsResult result) {
        return new WorkOrderMetricsResponseDto(
                result.completedWorkOrders(), result.averageExecutionMinutes());
    }
}
