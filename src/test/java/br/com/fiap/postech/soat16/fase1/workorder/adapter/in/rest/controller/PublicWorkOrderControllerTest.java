package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderTrackingResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.application.WorkOrderService;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.ExpiredWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicWorkOrderController — Unit Tests")
class PublicWorkOrderControllerTest {

    @Mock
    private WorkOrderService service;

    private PublicWorkOrderController controller;

    private static final UUID WORK_ORDER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID ESTIMATE_ID = UUID.fromString("c3b79cde-2872-4053-9622-37605bf124a3");
    private static final String DECISION_TOKEN = "signed-decision-token";
    private static final String TRACKING_TOKEN = "signed-tracking-token";

    private WorkOrderTrackingResult tracking;

    @BeforeEach
    void setUp() {
        controller = new PublicWorkOrderController(service);
        tracking = new WorkOrderTrackingResult(WORK_ORDER_ID, WorkOrderStatus.WAITING_APPROVAL,
            LocalDateTime.of(2026, 1, 10, 9, 0), null, null);
    }

    @Nested
    @DisplayName("GET /v1/public/work-orders/tracking/{token} — track")
    class Track {

        @Test
        @DisplayName("deve devolver o andamento da OS pelo link recebido, sem autenticação")
        void shouldReturnTracking() {
            when(service.track(TRACKING_TOKEN)).thenReturn(Uni.createFrom().item(tracking));

            WorkOrderTrackingResponseDto result = controller.track(TRACKING_TOKEN).await().indefinitely();

            assertEquals(WORK_ORDER_ID, result.workOrderId());
            assertEquals(WorkOrderStatus.WAITING_APPROVAL, result.status());
            verify(service).track(TRACKING_TOKEN);
        }

        @Test
        @DisplayName("deve propagar o link de acompanhamento inválido")
        void shouldPropagateInvalidTrackingToken() {
            when(service.track(TRACKING_TOKEN))
                .thenReturn(Uni.createFrom().failure(new InvalidWorkOrderTrackingTokenException()));

            assertThrows(InvalidWorkOrderTrackingTokenException.class,
                () -> controller.track(TRACKING_TOKEN).await().indefinitely());
        }

        @Test
        @DisplayName("deve propagar o link de acompanhamento expirado")
        void shouldPropagateExpiredTrackingToken() {
            when(service.track(TRACKING_TOKEN))
                .thenReturn(Uni.createFrom().failure(new ExpiredWorkOrderTrackingTokenException()));

            assertThrows(ExpiredWorkOrderTrackingTokenException.class,
                () -> controller.track(TRACKING_TOKEN).await().indefinitely());
        }

        @Test
        @DisplayName("deve propagar a ordem de serviço inexistente")
        void shouldPropagateNotFound() {
            when(service.track(TRACKING_TOKEN))
                .thenReturn(Uni.createFrom().failure(new WorkOrderNotFoundException()));

            assertThrows(WorkOrderNotFoundException.class,
                () -> controller.track(TRACKING_TOKEN).await().indefinitely());
        }
    }

    @Test
    @DisplayName("decisão pública responde 503 sem consumir o token")
    void decisionIsUnavailable() {
        var error = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> controller.decideEstimate(DECISION_TOKEN).await().indefinitely());
        assertEquals(503, error.getResponse().getStatus());
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
