package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.ForbiddenException;

import org.eclipse.microprofile.jwt.JsonWebToken;
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
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.application.WorkOrderService;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.EstimateResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateStatus;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerWorkOrderController — Unit Tests")
class CustomerWorkOrderControllerTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("0b6a3c1e-8d0c-4f39-9f38-6b1f7f5b2a11");
    private static final UUID WORK_ORDER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID ESTIMATE_ID = UUID.fromString("c3b79cde-2872-4053-9622-37605bf124a3");

    @Mock
    private WorkOrderService service;

    @Mock
    private JsonWebToken token;

    private CustomerWorkOrderController controller;

    private WorkOrderResult workOrder;

    @BeforeEach
    void setUp() {
        controller = new CustomerWorkOrderController(service, token);
        workOrder = new WorkOrderResult(WORK_ORDER_ID, CUSTOMER_ID, UUID.randomUUID(), "Troca de óleo", null,
                WorkOrderStatus.WAITING_APPROVAL, null, null, null, BigDecimal.TEN, null, null);
    }

    private EstimateResult estimate(EstimateStatus status) {
        return new EstimateResult(ESTIMATE_ID, WORK_ORDER_ID, status, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.TEN, null, null, List.of());
    }

    @Nested
    @DisplayName("identidade do cliente")
    class Identity {

        @Test
        @DisplayName("deve recusar com 403 o token sem sub")
        void shouldRejectTokenWithoutSubject() {
            when(token.getSubject()).thenReturn(null);

            assertThrows(ForbiddenException.class, () -> controller.findMyWorkOrder(WORK_ORDER_ID));
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("deve recusar com 403 o token cujo sub não é o id de um cliente")
        void shouldRejectTokenWhoseSubjectIsNotCustomerId() {
            when(token.getSubject()).thenReturn("admin");

            assertThrows(ForbiddenException.class, () -> controller.findMyWorkOrder(WORK_ORDER_ID));
            verifyNoInteractions(service);
        }
    }

    @Nested
    @DisplayName("com token de cliente")
    class WithCustomerToken {

        @BeforeEach
        void authenticate() {
            when(token.getSubject()).thenReturn(CUSTOMER_ID.toString());
        }

        @Test
        @DisplayName("deve listar as ordens do cliente do token")
        void shouldListCustomerWorkOrders() {
            PageableRequestDto pageable = mock(PageableRequestDto.class);
            when(pageable.getPage()).thenReturn(0);
            when(pageable.getSize()).thenReturn(10);
            when(service.findCustomerWorkOrders(CUSTOMER_ID, 0, 10))
                    .thenReturn(Uni.createFrom().item(PagedResult.of(List.of(workOrder), 0, 10, 1L)));

            PageableResponseDto<WorkOrderResponseDto> result =
                    controller.findMyWorkOrders(pageable).await().indefinitely();

            assertEquals(1, result.content().size());
            assertEquals(WORK_ORDER_ID, result.content().getFirst().workOrderId());
        }

        @Test
        @DisplayName("deve detalhar a ordem do cliente do token")
        void shouldReturnCustomerWorkOrder() {
            when(service.findCustomerWorkOrder(CUSTOMER_ID, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(workOrder));

            WorkOrderResponseDto result = controller.findMyWorkOrder(WORK_ORDER_ID).await().indefinitely();

            assertEquals(WORK_ORDER_ID, result.workOrderId());
        }

        @Test
        @DisplayName("deve propagar WorkOrderNotFoundException para ordem de outro cliente")
        void shouldPropagateNotFound() {
            when(service.findCustomerWorkOrder(CUSTOMER_ID, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().failure(new WorkOrderNotFoundException()));

            assertThrows(WorkOrderNotFoundException.class,
                    () -> controller.findMyWorkOrder(WORK_ORDER_ID).await().indefinitely());
        }

        @Test
        @DisplayName("deve aprovar o orçamento em nome do cliente do token")
        void shouldApproveEstimate() {
            when(service.approveCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, ESTIMATE_ID))
                    .thenReturn(Uni.createFrom().item(estimate(EstimateStatus.APPROVED)));

            EstimateResponseDto result = controller.approveMyEstimate(WORK_ORDER_ID, ESTIMATE_ID)
                    .await().indefinitely();

            assertEquals(EstimateStatus.APPROVED, result.status());
            verify(service).approveCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, ESTIMATE_ID);
        }

        @Test
        @DisplayName("deve recusar o orçamento em nome do cliente do token")
        void shouldRejectEstimate() {
            when(service.rejectCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, ESTIMATE_ID))
                    .thenReturn(Uni.createFrom().item(estimate(EstimateStatus.REJECTED)));

            EstimateResponseDto result = controller.rejectMyEstimate(WORK_ORDER_ID, ESTIMATE_ID)
                    .await().indefinitely();

            assertEquals(EstimateStatus.REJECTED, result.status());
            verify(service).rejectCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, ESTIMATE_ID);
        }
    }
}
