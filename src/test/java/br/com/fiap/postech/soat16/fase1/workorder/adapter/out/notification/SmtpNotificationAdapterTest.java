package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateDecisionInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.Estimate;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateStatus;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmtpNotificationAdapter — Unit Tests")
class SmtpNotificationAdapterTest {

    private static final String BASE_URL = "https://oficina.example.com";
    private static final UUID WORK_ORDER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final String CUSTOMER_EMAIL = "cliente@example.com";

    @Mock
    private ReactiveMailer mailer;

    private SmtpNotificationAdapter adapter;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new SmtpNotificationAdapter(mailer, registry, BASE_URL);
    }

    @Nested
    @DisplayName("notifyEstimateAwaitingDecision")
    class NotifyEstimateAwaitingDecision {

        @Test
        @DisplayName("envia ao e-mail do cliente os dois links de decisão sob o endereço público")
        void sendsBothDecisionLinks() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);

            adapter.notifyEstimateAwaitingDecision(order, estimate(order), invitation())
                    .await().indefinitely();

            assertEquals(1, registry.get("workshop.integration.calls").tag("outcome", "success").counter().count());
            Mail mail = captureMail();
            assertEquals(List.of(CUSTOMER_EMAIL), mail.getTo());
            assertTrue(mail.getSubject().contains(WORK_ORDER_ID.toString()));
            assertTrue(mail.getText().contains(
                    BASE_URL + "/v1/public/work-orders/estimate-decisions/approve-token"));
            assertTrue(mail.getText().contains(
                    BASE_URL + "/v1/public/work-orders/estimate-decisions/reject-token"));
        }

        @Test
        @DisplayName("informa ao cliente o valor do orçamento e o prazo da decisão")
        void statesAmountAndDeadline() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);

            adapter.notifyEstimateAwaitingDecision(order, estimate(order), invitation())
                    .await().indefinitely();

            String body = captureMail().getText();
            assertTrue(body.contains("150.00"));
            assertTrue(body.contains("17/01/2026"));
        }

        @Test
        @DisplayName("não envia nada quando a ordem não tem e-mail de cliente")
        void skipsWhenCustomerHasNoEmail() {
            WorkOrder order = workOrder(null);

            adapter.notifyEstimateAwaitingDecision(order, estimate(order), invitation())
                    .await().indefinitely();

            verify(mailer, never()).send(any(Mail.class));
        }

        @Test
        @DisplayName("conclui sem erro quando o servidor de e-mail recusa a mensagem")
        void completesWhenMailServerRejectsMessage() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom()
                    .failure(new IllegalStateException("550 Too many emails per second")));
            WorkOrder order = workOrder(CUSTOMER_EMAIL);

            assertDoesNotThrow(() -> adapter
                    .notifyEstimateAwaitingDecision(order, estimate(order), invitation())
                    .await().indefinitely());
            assertEquals(1, registry.get("workshop.integration.calls").tag("outcome", "failure").counter().count());
        }
    }

    @Nested
    @DisplayName("notifyWorkOrderProgress")
    class NotifyWorkOrderProgress {

        @Test
        @DisplayName("envia ao cliente o link de acompanhamento sob o endereço público")
        void sendsTrackingLink() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);
            order.setStatus(WorkOrderStatus.RECEIVED);

            adapter.notifyWorkOrderProgress(order, tracking()).await().indefinitely();

            Mail mail = captureMail();
            assertEquals(List.of(CUSTOMER_EMAIL), mail.getTo());
            assertTrue(mail.getSubject().contains(WORK_ORDER_ID.toString()));
            assertTrue(mail.getText().contains(
                    BASE_URL + "/v1/public/work-orders/tracking/tracking-token"));
        }

        @Test
        @DisplayName("informa o estágio do atendimento e o prazo do link de acompanhamento")
        void statesStageAndDeadline() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);
            order.setStatus(WorkOrderStatus.IN_PROGRESS);

            adapter.notifyWorkOrderProgress(order, tracking()).await().indefinitely();

            String body = captureMail().getText();
            assertTrue(body.contains("execucao"));
            assertTrue(body.contains("09/02/2026"));
        }

        @Test
        @DisplayName("avisa o cliente de que o veículo está disponível para retirada ao concluir")
        void announcesVehicleReady() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);
            order.setStatus(WorkOrderStatus.COMPLETED);

            adapter.notifyWorkOrderProgress(order, tracking()).await().indefinitely();

            assertTrue(captureMail().getText().contains("retirada"));
        }

        @Test
        @DisplayName("não promete retirada quando o atendimento foi encerrado pela recusa do orçamento")
        void announcesAttendanceClosedByRejection() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().voidItem());
            WorkOrder order = workOrder(CUSTOMER_EMAIL);
            order.setStatus(WorkOrderStatus.COMPLETED);
            order.setCancelledAt(LocalDateTime.of(2026, 1, 10, 9, 30));

            adapter.notifyWorkOrderProgress(order, tracking()).await().indefinitely();

            String body = captureMail().getText();
            assertTrue(body.contains("recusado"));
            assertFalse(body.contains("retirada"));
        }

        @Test
        @DisplayName("não envia nada quando a ordem não tem e-mail de cliente")
        void skipsWhenCustomerHasNoEmail() {
            WorkOrder order = workOrder(null);
            order.setStatus(WorkOrderStatus.COMPLETED);

            adapter.notifyWorkOrderProgress(order, tracking()).await().indefinitely();

            verify(mailer, never()).send(any(Mail.class));
        }

        @Test
        @DisplayName("conclui sem erro quando o servidor de e-mail recusa a mensagem")
        void completesWhenMailServerRejectsMessage() {
            when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom()
                    .failure(new IllegalStateException("550 Too many emails per second")));
            WorkOrder order = workOrder(CUSTOMER_EMAIL);
            order.setStatus(WorkOrderStatus.RECEIVED);

            assertDoesNotThrow(() -> adapter
                    .notifyWorkOrderProgress(order, tracking()).await().indefinitely());
        }
    }

    private Mail captureMail() {
        ArgumentCaptor<Mail> captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer).send(captor.capture());
        return captor.getValue();
    }

    private EstimateDecisionInvitation invitation() {
        return new EstimateDecisionInvitation("approve-token", "reject-token",
                LocalDateTime.of(2026, 1, 17, 9, 30));
    }

    private WorkOrderTrackingInvitation tracking() {
        return new WorkOrderTrackingInvitation("tracking-token", LocalDateTime.of(2026, 2, 9, 9, 30));
    }

    private WorkOrder workOrder(String customerEmail) {
        Customer customer = new Customer();
        customer.setFirstName("Ana");
        customer.setEmail(customerEmail);

        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(WORK_ORDER_ID);
        workOrder.setCustomer(customer);
        workOrder.setPriority(WorkOrderPriority.MEDIUM);
        workOrder.setStatus(WorkOrderStatus.WAITING_APPROVAL);
        return workOrder;
    }

    private Estimate estimate(WorkOrder workOrder) {
        Estimate estimate = new Estimate();
        estimate.setId(UUID.randomUUID());
        estimate.setWorkOrder(workOrder);
        estimate.setStatus(EstimateStatus.PENDING);
        estimate.setTotalAmount(new BigDecimal("150.00"));
        return estimate;
    }
}
