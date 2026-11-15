package br.com.fiap.postech.soat16.fase1.workorder.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.exception.VehicleNotFoundException;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.ChangeWorkOrderStatusCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.mapper.WorkOrderMapper;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateProgressPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderEventJournalPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderNotificationPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderParticipantLookupPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderPersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingTokenSignaturePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.ExpiredWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderStatusTransitionException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderLockedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderHistory;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderTrackingToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService — Unit Tests")
class WorkOrderServiceTest {

    @Mock
    private WorkOrderPersistencePort repository;

    @Mock
    private WorkOrderEventJournalPort eventJournal;

    @Mock
    private EstimateProgressPort estimateProgress;

    @Mock
    private WorkOrderParticipantLookupPort participants;

    @Mock
    private WorkOrderMapper mapper;

    @Mock
    private WorkOrderNotificationPort notificationService;

    @Mock
    private WorkOrderTrackingTokenSignaturePort trackingTokenSignature;

    private WorkOrderService service;

    private static final UUID WORK_ORDER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID VEHICLE_ID = UUID.randomUUID();
    private static final UUID SERVICE_ITEM_ID = UUID.randomUUID();
    private static final UUID PART_ID = UUID.randomUUID();

    private WorkOrder entity;
    private WorkOrderResult response;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(repository, eventJournal, estimateProgress, participants,
                mapper, notificationService, trackingTokenSignature);

        lenient().when(estimateProgress.findProgress(any())).thenReturn(Uni.createFrom().nullItem());
        lenient().when(eventJournal.recordOpening(org.mockito.ArgumentMatchers.nullable(UUID.class), any()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient().when(notificationService.notifyWorkOrderProgress(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient().when(trackingTokenSignature.sign(any(WorkOrderTrackingToken.class)))
                .thenReturn("signed-tracking-token");


        entity = new WorkOrder();
        entity.setId(WORK_ORDER_ID);
        entity.setStatus(WorkOrderStatus.RECEIVED);

        response = new WorkOrderResult(WORK_ORDER_ID, CUSTOMER_ID, VEHICLE_ID, "desc", null,
                WorkOrderStatus.RECEIVED, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("findOperationalQueue")
    class FindOperationalQueue {

        @Test
        @DisplayName("deve paginar a fila operacional pelo total de ordens ainda em atendimento")
        void shouldReturnPaginatedResponse() {
            when(repository.findOperationalQueuePage(0, 10)).thenReturn(Uni.createFrom().item(List.of(entity)));
            when(repository.countOperationalQueue()).thenReturn(Uni.createFrom().item(1L));
            when(mapper.toResult(entity)).thenReturn(response);

            PagedResult<WorkOrderResult> result = service.findOperationalQueue(0, 10).await().indefinitely();

            assertNotNull(result);
            assertEquals(1, result.content().size());
            assertEquals(1L, result.totalElements());
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should return work order response when found")
        void shouldReturnWhenFound() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            WorkOrderResult result = service.findById(WORK_ORDER_ID).await().indefinitely();

            assertEquals(WORK_ORDER_ID, result.workOrderId());
        }

        @Test
        @DisplayName("should throw WorkOrderNotFoundException when not found")
        void shouldThrowWhenMissing() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.findById(WORK_ORDER_ID).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("track")
    class Track {

        private static final String SIGNED_TOKEN = "signed-tracking-token";

        @Test
        @DisplayName("deve devolver apenas o andamento da OS do link apresentado")
        void shouldReturnTheTrackedWorkOrder() {
            WorkOrderTrackingResult tracking = new WorkOrderTrackingResult(WORK_ORDER_ID,
                    WorkOrderStatus.RECEIVED, LocalDateTime.now(), null, null);
            when(trackingTokenSignature.read(SIGNED_TOKEN)).thenReturn(
                    WorkOrderTrackingToken.issue(WORK_ORDER_ID, LocalDateTime.now()));
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(mapper.toTrackingResult(entity)).thenReturn(tracking);

            WorkOrderTrackingResult result = service.track(SIGNED_TOKEN).await().indefinitely();

            assertEquals(WORK_ORDER_ID, result.workOrderId());
            assertEquals(WorkOrderStatus.RECEIVED, result.status());
        }

        @Test
        @DisplayName("não deve consultar a OS quando o link está fora do prazo de trinta dias")
        void shouldRejectExpiredTrackingLink() {
            when(trackingTokenSignature.read(SIGNED_TOKEN)).thenReturn(
                    WorkOrderTrackingToken.issue(WORK_ORDER_ID, LocalDateTime.now().minusDays(31)));

            assertThrows(ExpiredWorkOrderTrackingTokenException.class,
                    () -> service.track(SIGNED_TOKEN).await().indefinitely());
            verify(repository, never()).findByWorkOrderId(WORK_ORDER_ID);
        }

        @Test
        @DisplayName("não deve consultar a OS quando o link não foi emitido pela oficina")
        void shouldRejectForgedTrackingLink() {
            when(trackingTokenSignature.read(SIGNED_TOKEN))
                    .thenThrow(new InvalidWorkOrderTrackingTokenException());

            assertThrows(InvalidWorkOrderTrackingTokenException.class,
                    () -> service.track(SIGNED_TOKEN).await().indefinitely());
            verify(repository, never()).findByWorkOrderId(WORK_ORDER_ID);
        }

        @Test
        @DisplayName("deve lançar WorkOrderNotFoundException quando a OS do link não existe mais")
        void shouldThrowWhenTrackedWorkOrderMissing() {
            when(trackingTokenSignature.read(SIGNED_TOKEN)).thenReturn(
                    WorkOrderTrackingToken.issue(WORK_ORDER_ID, LocalDateTime.now()));
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.track(SIGNED_TOKEN).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("aviso de acompanhamento ao cliente")
    class ProgressNotification {

        @Test
        @DisplayName("deve convidar o cliente a acompanhar a OS na abertura, com link de trinta dias")
        void shouldInviteToTrackOnOpening() {
            LocalDateTime beforeOpening = LocalDateTime.now();
            when(participants.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(new Customer()));
            when(participants.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(new Vehicle()));
            when(repository.save(any(WorkOrder.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item((WorkOrder) invocation.getArgument(0)));

            service.create(new OpenWorkOrderCommand(CUSTOMER_ID, VEHICLE_ID, "desc", null, null,
                    List.of(), List.of())).await().indefinitely();

            WorkOrderTrackingInvitation invitation = captureInvitation();
            assertEquals("signed-tracking-token", invitation.trackingToken());
            assertTrue(invitation.expiresAt().isAfter(beforeOpening.plusDays(29)));
        }

        @Test
        @DisplayName("deve avisar o cliente a cada mudança de status")
        void shouldNotifyOnEveryStatusChange() {
            entity.setStatus(WorkOrderStatus.RECEIVED);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));

            service.updateStatus(WORK_ORDER_ID, new ChangeWorkOrderStatusCommand(WorkOrderStatus.DIAGNOSIS))
                    .await().indefinitely();

            verify(notificationService).notifyWorkOrderProgress(any(), any());
        }





        private WorkOrderTrackingInvitation captureInvitation() {
            ArgumentCaptor<WorkOrderTrackingInvitation> captor =
                    ArgumentCaptor.forClass(WorkOrderTrackingInvitation.class);
            verify(notificationService).notifyWorkOrderProgress(any(), captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        private OpenWorkOrderCommand openCommand(
                List<OpenWorkOrderCommand.RequestedService> services,
                List<OpenWorkOrderCommand.RequestedPart> parts) {
            return new OpenWorkOrderCommand(CUSTOMER_ID, VEHICLE_ID, "desc", null, null, services, parts);
        }

        private void givenCustomerAndVehicleExist() {
            when(participants.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(new Customer()));
            when(participants.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(new Vehicle()));
            when(repository.save(any(WorkOrder.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (WorkOrder) invocation.getArgument(0)));
        }

        @Test
        @DisplayName("should persist work order when customer and vehicle exist")
        void shouldPersistWhenCustomerAndVehicleExist() {
            Customer customer = new Customer();
            Vehicle vehicle = new Vehicle();
            OpenWorkOrderCommand request = openCommand(List.of(), List.of());

            when(participants.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(participants.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(vehicle));
            when(repository.save(any(WorkOrder.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (WorkOrder) invocation.getArgument(0)));

            assertDoesNotThrow(() -> service.create(request).await().indefinitely());
            ArgumentCaptor<WorkOrder> orderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
            verify(repository).save(orderCaptor.capture());
            assertEquals(WorkOrderStatus.RECEIVED, orderCaptor.getValue().getStatus());
            assertEquals(customer, orderCaptor.getValue().getCustomer());
            assertEquals(vehicle, orderCaptor.getValue().getVehicle());
        }

        @Test
        @DisplayName("registra a solicitação inicial sem consultar catálogo nem criar orçamento local")
        void publishesInitialRequestWithoutCatalogLookup() {
            givenCustomerAndVehicleExist();
            when(mapper.toResult(any(WorkOrder.class))).thenReturn(response);
            OpenWorkOrderCommand request = openCommand(
                    List.of(new OpenWorkOrderCommand.RequestedService(SERVICE_ITEM_ID)),
                    List.of(new OpenWorkOrderCommand.RequestedPart(PART_ID, 3)));
            var result = service.create(request).await().indefinitely();
            verify(eventJournal).recordOpening(org.mockito.ArgumentMatchers.nullable(UUID.class), org.mockito.ArgumentMatchers.eq(request));
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer does not exist")
        void shouldThrowWhenCustomerMissing() {
            OpenWorkOrderCommand request = openCommand(List.of(), List.of());
            when(participants.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(CustomerNotFoundException.class,
                    () -> service.create(request).await().indefinitely());
            verify(repository, never()).save(any(WorkOrder.class));
        }

        @Test
        @DisplayName("should throw VehicleNotFoundException when vehicle does not exist")
        void shouldThrowWhenVehicleMissing() {
            Customer customer = new Customer();
            OpenWorkOrderCommand request = openCommand(List.of(), List.of());

            when(participants.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(participants.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(VehicleNotFoundException.class,
                    () -> service.create(request).await().indefinitely());
            verify(repository, never()).save(any(WorkOrder.class));
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("should move to the next status and record history")
        void shouldMoveToNextStatusAndRecordHistory() {
            entity.setStatus(WorkOrderStatus.RECEIVED);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.DIAGNOSIS);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.updateStatus(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(WorkOrderStatus.DIAGNOSIS, entity.getStatus());
            verify(repository).saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class));
        }

        @Test
        @DisplayName("should reject jumping directly to COMPLETED")
        void shouldRejectCompletedViaGenericEndpoint() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.COMPLETED);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(InvalidWorkOrderStatusTransitionException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should reject skipping stages")
        void shouldRejectSkippingStages() {
            entity.setStatus(WorkOrderStatus.RECEIVED);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.IN_PROGRESS);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(InvalidWorkOrderStatusTransitionException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should reject DELIVERED unless current status is COMPLETED")
        void shouldRejectDeliveredFromNonCompleted() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.DELIVERED);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(InvalidWorkOrderStatusTransitionException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should allow DELIVERED from COMPLETED")
        void shouldAllowDeliveredFromCompleted() {
            entity.setStatus(WorkOrderStatus.COMPLETED);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.DELIVERED);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.updateStatus(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(WorkOrderStatus.DELIVERED, entity.getStatus());
        }

        @Test
        @DisplayName("should throw WorkOrderLockedException when current status is DELIVERED")
        void shouldThrowLockedWhenDelivered() {
            entity.setStatus(WorkOrderStatus.DELIVERED);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.DIAGNOSIS);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderLockedException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }





        @Test
        @DisplayName("should throw WorkOrderNotFoundException when work order does not exist")
        void shouldThrowWhenMissing() {
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.DIAGNOSIS);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("canal do cliente autenticado pelo CPF")
    class CustomerChannel {

        private static final UUID OTHER_CUSTOMER_ID = UUID.randomUUID();

        private final UUID estimateId = UUID.randomUUID();

        @BeforeEach
        void ownWorkOrder() {
            Customer owner = new Customer();
            owner.setId(CUSTOMER_ID);
            entity.setCustomer(owner);
        }





        @Test
        @DisplayName("deve paginar apenas as ordens do cliente autenticado")
        void shouldPageCustomerWorkOrders() {
            when(repository.findPageByCustomerId(CUSTOMER_ID, 0, 10)).thenReturn(Uni.createFrom().item(List.of(entity)));
            when(repository.countByCustomerId(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(1L));
            when(mapper.toResult(entity)).thenReturn(response);

            PagedResult<WorkOrderResult> result = service.findCustomerWorkOrders(CUSTOMER_ID, 0, 10)
                    .await().indefinitely();

            assertEquals(1, result.content().size());
            assertEquals(1L, result.totalElements());
        }

        @Test
        @DisplayName("deve detalhar a ordem que pertence ao cliente autenticado")
        void shouldReturnOwnWorkOrder() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            WorkOrderResult result = service.findCustomerWorkOrder(CUSTOMER_ID, WORK_ORDER_ID).await().indefinitely();

            assertEquals(WORK_ORDER_ID, result.workOrderId());
        }

        @Test
        @DisplayName("deve responder como inexistente a ordem de outro cliente")
        void shouldHideWorkOrderOfAnotherCustomer() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.findCustomerWorkOrder(OTHER_CUSTOMER_ID, WORK_ORDER_ID).await().indefinitely());
        }

        @Test
        @DisplayName("deve lançar WorkOrderNotFoundException quando a ordem não existir")
        void shouldThrowWhenWorkOrderMissing() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.findCustomerWorkOrder(CUSTOMER_ID, WORK_ORDER_ID).await().indefinitely());
        }






    }

    @Nested
    @DisplayName("averageExecutionTime")
    class AverageExecutionTime {

        @Test
        @DisplayName("should return zero and null average when there are no closed work orders")
        void shouldReturnZeroWhenNoneClosed() {
            when(repository.findClosed()).thenReturn(Uni.createFrom().item(List.of()));

            var result = service.averageExecutionTime().await().indefinitely();

            assertEquals(0, result.completedWorkOrders());
            assertNull(result.averageExecutionMinutes());
        }

        @Test
        @DisplayName("should compute the average execution time in minutes across closed work orders")
        void shouldComputeAverage() {
            LocalDateTime opened = LocalDateTime.of(2026, 1, 1, 8, 0);
            WorkOrder first = new WorkOrder();
            first.setOpenedAt(opened);
            first.setClosedAt(opened.plusMinutes(60));
            WorkOrder second = new WorkOrder();
            second.setOpenedAt(opened);
            second.setClosedAt(opened.plusMinutes(120));

            when(repository.findClosed()).thenReturn(Uni.createFrom().item(List.of(first, second)));

            var result = service.averageExecutionTime().await().indefinitely();

            assertEquals(2, result.completedWorkOrders());
            assertEquals(90.0, result.averageExecutionMinutes());
        }
    }
}
