package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableRequestDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.WorkOrderRestMapper;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.EstimateItemRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.EstimateRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderCloseRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderServiceRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.request.WorkOrderStatusUpdateRequestDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.OpenedWorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderMetricsResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.application.WorkOrderService;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.OpenWorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderMetricsResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderLockedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderController — Unit Tests")
class WorkOrderControllerTest {

    @Mock
    private WorkOrderService service;

    private WorkOrderController controller;

    private static final UUID FIXED_UUID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private WorkOrderResult response;

    @BeforeEach
    void setUp() {
        controller = new WorkOrderController(service);
        response = new WorkOrderResult(FIXED_UUID, UUID.randomUUID(), UUID.randomUUID(), "desc",
                WorkOrderPriority.MEDIUM, WorkOrderStatus.RECEIVED, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("GET /v1/work-orders — findOperationalQueue")
    class FindOperationalQueue {

        @Test
        @DisplayName("should return the paginated operational queue")
        void shouldReturnPaginatedList() {
            PageableRequestDto pageable = mock(PageableRequestDto.class);
            when(pageable.getPage()).thenReturn(0);
            when(pageable.getSize()).thenReturn(10);

            PagedResult<WorkOrderResult> page = PagedResult.of(List.of(response), 0, 10, 1L);
            when(service.findOperationalQueue(0, 10)).thenReturn(Uni.createFrom().item(page));

            PageableResponseDto<WorkOrderResponseDto> result =
                    controller.findOperationalQueue(pageable).await().indefinitely();

            assertEquals(1, result.content().size());
            verify(service).findOperationalQueue(0, 10);
        }
    }

    @Nested
    @DisplayName("GET /v1/work-orders/{id} — findById")
    class FindById {

        @Test
        @DisplayName("should return work order when found")
        void shouldReturnWhenFound() {
            when(service.findById(FIXED_UUID)).thenReturn(Uni.createFrom().item(response));

            WorkOrderResponseDto result = controller.findById(FIXED_UUID).await().indefinitely();

            assertNotNull(result);
            verify(service).findById(FIXED_UUID);
        }

        @Test
        @DisplayName("should propagate WorkOrderNotFoundException")
        void shouldPropagateNotFoundException() {
            when(service.findById(FIXED_UUID)).thenReturn(Uni.createFrom().failure(new WorkOrderNotFoundException()));

            assertThrows(WorkOrderNotFoundException.class,
                    () -> controller.findById(FIXED_UUID).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("GET /v1/work-orders/metrics/average-execution-time — averageExecutionTime")
    class AverageExecutionTime {

        @Test
        @DisplayName("should return the average execution metrics")
        void shouldReturnMetrics() {
            WorkOrderMetricsResult metrics = new WorkOrderMetricsResult(3, 45.5);
            when(service.averageExecutionTime()).thenReturn(Uni.createFrom().item(metrics));

            WorkOrderMetricsResponseDto result = controller.averageExecutionTime().await().indefinitely();

            assertEquals(3, result.completedWorkOrders());
            assertEquals(45.5, result.averageExecutionMinutes());
            verify(service).averageExecutionTime();
        }
    }

    @Nested
    @DisplayName("POST /v1/work-orders — create")
    class Create {

        @Test
        @DisplayName("should return HTTP 201 before the estimate is created asynchronously")
        void shouldReturn201WhenCreateSucceeds() {
            WorkOrderRequestDto dto = new WorkOrderRequestDto(
                    UUID.randomUUID(), UUID.randomUUID(), "desc", null, null, null, null);
            var command = WorkOrderRestMapper.toCommand(dto);
            when(service.create(command))
                    .thenReturn(Uni.createFrom().item(new OpenWorkOrderResult(response)));

            Response result = controller.create(dto).await().indefinitely();

            assertEquals(201, result.getStatus());
            assertEquals("/v1/work-orders/" + FIXED_UUID, result.getLocation().toString());
            OpenedWorkOrderResponseDto body = (OpenedWorkOrderResponseDto) result.getEntity();
            assertEquals(FIXED_UUID, body.workOrder().workOrderId());
            assertNull(body.estimate());
            verify(service).create(command);
        }

        @Test
        @DisplayName("should omit the estimate when the opening carries no initial request")
        void shouldOmitEstimateWhenNoInitialRequest() {
            WorkOrderRequestDto dto = new WorkOrderRequestDto(
                    UUID.randomUUID(), UUID.randomUUID(), "desc", null, null, null, null);
            var command = WorkOrderRestMapper.toCommand(dto);
            when(service.create(command))
                    .thenReturn(Uni.createFrom().item(new OpenWorkOrderResult(response)));

            Response result = controller.create(dto).await().indefinitely();

            assertNull(((OpenedWorkOrderResponseDto) result.getEntity()).estimate());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/work-orders/{id}/status — updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("should return updated work order")
        void shouldReturnUpdatedWorkOrder() {
            WorkOrderStatusUpdateRequestDto dto = new WorkOrderStatusUpdateRequestDto(WorkOrderStatus.DIAGNOSIS);
            var command = WorkOrderRestMapper.toCommand(dto);
            when(service.updateStatus(FIXED_UUID, command)).thenReturn(Uni.createFrom().item(response));

            WorkOrderResponseDto result = controller.updateStatus(FIXED_UUID, dto).await().indefinitely();

            assertNotNull(result);
            verify(service).updateStatus(FIXED_UUID, command);
        }

        @Test
        @DisplayName("should propagate WorkOrderLockedException")
        void shouldPropagateLockedException() {
            WorkOrderStatusUpdateRequestDto dto = new WorkOrderStatusUpdateRequestDto(WorkOrderStatus.DIAGNOSIS);
            var command = WorkOrderRestMapper.toCommand(dto);
            when(service.updateStatus(FIXED_UUID, command))
                    .thenReturn(Uni.createFrom().failure(new WorkOrderLockedException()));

            assertThrows(WorkOrderLockedException.class,
                    () -> controller.updateStatus(FIXED_UUID, dto).await().indefinitely());
        }
    }

    @Test
    @DisplayName("fluxos legados respondem 503 sem invocar o serviço")
    void legacyFlowsAreUnavailable() {
        UUID estimate = UUID.randomUUID();
        var create = new EstimateRequestDto(List.of(new EstimateItemRequestDto(UUID.randomUUID(), 1, BigDecimal.TEN)));
        var labor = new WorkOrderServiceRequestDto("Serviço", BigDecimal.TEN, null);
        var close = new WorkOrderCloseRequestDto(BigDecimal.TEN);
        var status = new WorkOrderStatusUpdateRequestDto(WorkOrderStatus.WAITING_APPROVAL);
        assertUnavailable(() -> controller.createEstimate(FIXED_UUID, create).await().indefinitely());
        assertUnavailable(() -> controller.approveEstimate(FIXED_UUID, estimate).await().indefinitely());
        assertUnavailable(() -> controller.rejectEstimate(FIXED_UUID, estimate).await().indefinitely());
        assertUnavailable(() -> controller.addService(FIXED_UUID, labor).await().indefinitely());
        assertUnavailable(() -> controller.close(FIXED_UUID, close).await().indefinitely());
        assertUnavailable(() -> controller.updateStatus(FIXED_UUID, status).await().indefinitely());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(jakarta.ws.rs.WebApplicationException.class, action);
        assertEquals(503, error.getResponse().getStatus());
    }
}
