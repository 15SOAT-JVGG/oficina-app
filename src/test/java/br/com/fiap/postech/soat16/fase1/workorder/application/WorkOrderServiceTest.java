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
import static org.mockito.Mockito.times;
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

import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.part.domain.model.Part;
import br.com.fiap.postech.soat16.fase1.servicecatalog.domain.exception.ServiceItemNotFoundException;
import br.com.fiap.postech.soat16.fase1.servicecatalog.domain.model.ServiceItem;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.exception.VehicleNotFoundException;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.AddWorkOrderServiceCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.ChangeWorkOrderStatusCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.CloseWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.CreateEstimateCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.CreateEstimateCommand.Item;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.mapper.EstimateMapper;
import br.com.fiap.postech.soat16.fase1.workorder.application.mapper.WorkOrderMapper;
import br.com.fiap.postech.soat16.fase1.workorder.application.mapper.WorkOrderServiceMapper;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateDecisionInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateDecisionTokenPersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimateDecisionTokenSignaturePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.EstimatePersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderNotificationPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderPersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderServicePersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingInvitation;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderTrackingTokenSignaturePort;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkshopCatalogPort;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.EstimateResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderServiceResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateAlreadyDecidedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateDecisionTokenAlreadyUsedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateNotApprovedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimatePartNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.ExpiredEstimateDecisionTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.ExpiredWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InsufficientPartStockException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidEstimateDecisionTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderStatusTransitionException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderTrackingTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderLockedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.Estimate;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.EstimateDecisionToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.EstimateItem;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderHistory;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderTrackingToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateDecision;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateStatus;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderService — Unit Tests")
class WorkOrderServiceTest {

    @Mock
    private WorkOrderPersistencePort repository;

    @Mock
    private EstimatePersistencePort estimateRepository;

    @Mock
    private WorkOrderServicePersistencePort serviceRepository;

    @Mock
    private WorkshopCatalogPort catalog;

    @Mock
    private WorkOrderMapper mapper;

    @Mock
    private EstimateMapper estimateMapper;

    @Mock
    private WorkOrderServiceMapper serviceMapper;

    @Mock
    private WorkOrderNotificationPort notificationService;

    @Mock
    private EstimateDecisionTokenPersistencePort decisionTokenRepository;

    @Mock
    private EstimateDecisionTokenSignaturePort decisionTokenSignature;

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
        service = new WorkOrderService(repository, estimateRepository, serviceRepository,
                decisionTokenRepository, catalog, mapper, estimateMapper, serviceMapper,
                notificationService, decisionTokenSignature, trackingTokenSignature);

        lenient().when(notificationService.notifyEstimateAwaitingDecision(any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient().when(notificationService.notifyWorkOrderProgress(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient().when(trackingTokenSignature.sign(any(WorkOrderTrackingToken.class)))
                .thenReturn("signed-tracking-token");
        lenient().when(decisionTokenRepository.save(any(EstimateDecisionToken.class)))
                .thenAnswer(invocation -> Uni.createFrom().item(
                        (EstimateDecisionToken) invocation.getArgument(0)));
        lenient().when(decisionTokenSignature.sign(any(EstimateDecisionToken.class)))
                .thenAnswer(invocation -> "signed-"
                        + ((EstimateDecisionToken) invocation.getArgument(0)).getDecision());

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
            when(catalog.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(new Customer()));
            when(catalog.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(new Vehicle()));
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

        @Test
        @DisplayName("deve avisar o cliente ao concluir a OS")
        void shouldNotifyOnClosing() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            entity.setEstimatedValue(BigDecimal.valueOf(100));
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));

            service.close(WORK_ORDER_ID, new CloseWorkOrderCommand(null)).await().indefinitely();

            verify(notificationService).notifyWorkOrderProgress(any(), any());
        }

        @Test
        @DisplayName("não deve avisar quando a decisão do orçamento não muda o status da OS")
        void shouldNotNotifyWithoutStatusChange() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setId(estimateId);
            estimate.setWorkOrder(entity);
            estimate.setStatus(EstimateStatus.PENDING);
            estimate.setTotalAmount(BigDecimal.valueOf(50));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            when(repository.save(entity)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));

            service.approveEstimate(WORK_ORDER_ID, estimateId).await().indefinitely();

            verify(notificationService, never()).notifyWorkOrderProgress(any(), any());
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
            when(catalog.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(new Customer()));
            when(catalog.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(new Vehicle()));
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

            when(catalog.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(catalog.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().item(vehicle));
            when(repository.save(any(WorkOrder.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (WorkOrder) invocation.getArgument(0)));

            assertDoesNotThrow(() -> service.create(request).await().indefinitely());
            ArgumentCaptor<WorkOrder> orderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
            verify(repository).save(orderCaptor.capture());
            assertEquals(WorkOrderStatus.RECEIVED, orderCaptor.getValue().getStatus());
            assertEquals(customer, orderCaptor.getValue().getCustomer());
            assertEquals(vehicle, orderCaptor.getValue().getVehicle());
            verify(estimateRepository, never()).save(any(Estimate.class));
        }

        @Test
        @DisplayName("should create the pending estimate from the initial service and part request")
        void shouldCreatePendingEstimateFromInitialRequest() {
            givenCustomerAndVehicleExist();

            ServiceItem serviceItem = new ServiceItem();
            serviceItem.setId(SERVICE_ITEM_ID);
            serviceItem.setName("Alinhamento");
            serviceItem.setBasePrice(new BigDecimal("120.00"));
            Part part = new Part("Filtro", "Filtro", new BigDecimal("50.00"), 10, "UN");

            when(catalog.findServiceItemById(SERVICE_ITEM_ID)).thenReturn(Uni.createFrom().item(serviceItem));
            when(catalog.findPartById(PART_ID)).thenReturn(Uni.createFrom().item(part));
            when(serviceRepository.save(any(
                    br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService)
                                    invocation.getArgument(0)));
            when(estimateRepository.save(any(Estimate.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (Estimate) invocation.getArgument(0)));

            OpenWorkOrderCommand request = openCommand(
                    List.of(new OpenWorkOrderCommand.RequestedService(SERVICE_ITEM_ID)),
                    List.of(new OpenWorkOrderCommand.RequestedPart(PART_ID, 3)));

            service.create(request).await().indefinitely();

            ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
            verify(estimateRepository).save(estimateCaptor.capture());
            Estimate estimate = estimateCaptor.getValue();
            assertEquals(EstimateStatus.PENDING, estimate.getStatus());
            assertEquals(0, new BigDecimal("150.00").compareTo(estimate.getPartsAmount()));
            assertEquals(0, new BigDecimal("120.00").compareTo(estimate.getLaborAmount()));
            assertEquals(0, new BigDecimal("270.00").compareTo(estimate.getTotalAmount()));
            assertEquals(0, new BigDecimal("270.00").compareTo(estimate.getWorkOrder().getEstimatedValue()));
            assertEquals(WorkOrderStatus.RECEIVED, estimate.getWorkOrder().getStatus());
        }

        @Test
        @DisplayName("should snapshot the catalog base price on the requested service line")
        void shouldSnapshotServicePrice() {
            givenCustomerAndVehicleExist();

            ServiceItem serviceItem = new ServiceItem();
            serviceItem.setId(SERVICE_ITEM_ID);
            serviceItem.setName("Alinhamento");
            serviceItem.setBasePrice(new BigDecimal("120.00"));

            when(catalog.findServiceItemById(SERVICE_ITEM_ID)).thenReturn(Uni.createFrom().item(serviceItem));
            when(serviceRepository.save(any(
                    br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService)
                                    invocation.getArgument(0)));
            when(estimateRepository.save(any(Estimate.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (Estimate) invocation.getArgument(0)));

            service.create(openCommand(
                    List.of(new OpenWorkOrderCommand.RequestedService(SERVICE_ITEM_ID)), List.of()))
                    .await().indefinitely();

            var serviceCaptor = ArgumentCaptor.forClass(
                    br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService.class);
            verify(serviceRepository).save(serviceCaptor.capture());
            assertEquals("Alinhamento", serviceCaptor.getValue().getDescription());
            assertEquals(0, new BigDecimal("120.00").compareTo(serviceCaptor.getValue().getPrice()));
            assertEquals(serviceItem, serviceCaptor.getValue().getServiceItem());
        }

        @Test
        @DisplayName("should throw ServiceItemNotFoundException and skip the estimate when the item is unknown")
        void shouldThrowWhenRequestedServiceItemMissing() {
            givenCustomerAndVehicleExist();
            when(catalog.findServiceItemById(SERVICE_ITEM_ID)).thenReturn(Uni.createFrom().nullItem());

            OpenWorkOrderCommand request = openCommand(
                    List.of(new OpenWorkOrderCommand.RequestedService(SERVICE_ITEM_ID)), List.of());

            assertThrows(ServiceItemNotFoundException.class,
                    () -> service.create(request).await().indefinitely());
            verify(estimateRepository, never()).save(any(Estimate.class));
        }

        @Test
        @DisplayName("should throw EstimatePartNotFoundException and skip the estimate when the part is unknown")
        void shouldThrowWhenRequestedPartMissing() {
            givenCustomerAndVehicleExist();
            when(catalog.findPartById(PART_ID)).thenReturn(Uni.createFrom().nullItem());

            OpenWorkOrderCommand request = openCommand(
                    List.of(), List.of(new OpenWorkOrderCommand.RequestedPart(PART_ID, 1)));

            assertThrows(EstimatePartNotFoundException.class,
                    () -> service.create(request).await().indefinitely());
            verify(estimateRepository, never()).save(any(Estimate.class));
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer does not exist")
        void shouldThrowWhenCustomerMissing() {
            OpenWorkOrderCommand request = openCommand(List.of(), List.of());
            when(catalog.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(CustomerNotFoundException.class,
                    () -> service.create(request).await().indefinitely());
            verify(repository, never()).save(any(WorkOrder.class));
        }

        @Test
        @DisplayName("should throw VehicleNotFoundException when vehicle does not exist")
        void shouldThrowWhenVehicleMissing() {
            Customer customer = new Customer();
            OpenWorkOrderCommand request = openCommand(List.of(), List.of());

            when(catalog.findCustomerById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(catalog.findVehicleById(VEHICLE_ID)).thenReturn(Uni.createFrom().nullItem());

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
        @DisplayName("should throw EstimateNotApprovedException when starting execution without approved estimate")
        void shouldThrowWhenStartingWithoutApprovedEstimate() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.IN_PROGRESS);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(false));

            assertThrows(EstimateNotApprovedException.class,
                    () -> service.updateStatus(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should allow starting execution when an approved estimate exists")
        void shouldAllowStartingWithApprovedEstimate() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            ChangeWorkOrderStatusCommand request = new ChangeWorkOrderStatusCommand(WorkOrderStatus.IN_PROGRESS);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.updateStatus(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(WorkOrderStatus.IN_PROGRESS, entity.getStatus());
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
    @DisplayName("createEstimate")
    class CreateEstimate {

        @Test
        @DisplayName("should build items, compute totalAmount and persist the estimate")
        void shouldBuildAndPersistEstimate() {
            UUID partId = UUID.randomUUID();
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN");
            Item itemDto = new Item(partId, 2, BigDecimal.TEN);
            CreateEstimateCommand request = new CreateEstimateCommand(List.of(itemDto));

            EstimateResult estimateResponse = new EstimateResult(
                    UUID.randomUUID(), WORK_ORDER_ID, EstimateStatus.PENDING, BigDecimal.valueOf(20), BigDecimal.ZERO,
                    BigDecimal.valueOf(20), null, null, List.of());

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(catalog.findPartById(partId)).thenReturn(Uni.createFrom().item(part));
            when(serviceRepository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(List.of()));
            when(estimateRepository.save(any(Estimate.class)))
                    .thenAnswer(invocation -> Uni.createFrom().item(
                            (Estimate) invocation.getArgument(0)));
            when(repository.save(entity)).thenReturn(Uni.createFrom().item(entity));
            when(estimateMapper.toResult(any(Estimate.class))).thenReturn(estimateResponse);

            EstimateResult result = service.createEstimate(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(BigDecimal.valueOf(20), result.totalAmount());
            verify(estimateRepository).save(any(Estimate.class));
        }

        @Test
        @DisplayName("should throw EstimatePartNotFoundException when a referenced part does not exist")
        void shouldThrowWhenPartMissing() {
            UUID partId = UUID.randomUUID();
            Item itemDto = new Item(partId, 2, BigDecimal.TEN);
            CreateEstimateCommand request = new CreateEstimateCommand(List.of(itemDto));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(catalog.findPartById(partId)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(EstimatePartNotFoundException.class,
                    () -> service.createEstimate(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should throw WorkOrderLockedException when work order is delivered")
        void shouldThrowWhenLocked() {
            entity.setStatus(WorkOrderStatus.DELIVERED);
            CreateEstimateCommand request = new CreateEstimateCommand(
                    List.of(new Item(UUID.randomUUID(), 1, BigDecimal.ONE)));
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderLockedException.class,
                    () -> service.createEstimate(WORK_ORDER_ID, request).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("approveEstimate")
    class ApproveEstimate {

        @Test
        @DisplayName("deve aprovar o orçamento, atualizar o valor estimado e iniciar a execução")
        void shouldApproveAndAdvanceStatus() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setId(estimateId);
            estimate.setWorkOrder(entity);
            estimate.setStatus(EstimateStatus.PENDING);
            estimate.setTotalAmount(BigDecimal.valueOf(100));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(new EstimateResult(
                    estimateId, WORK_ORDER_ID, EstimateStatus.APPROVED, BigDecimal.valueOf(100), BigDecimal.ZERO,
                    BigDecimal.valueOf(100), null, null, List.of()));

            service.approveEstimate(WORK_ORDER_ID, estimateId).await().indefinitely();

            assertEquals(EstimateStatus.APPROVED, estimate.getStatus());
            assertEquals(WorkOrderStatus.IN_PROGRESS, entity.getStatus());
            assertEquals(BigDecimal.valueOf(100), entity.getEstimatedValue());
        }

        @Test
        @DisplayName("should approve estimate without forcing a status change when not WAITING_APPROVAL")
        void shouldApproveWithoutForcingStatusChange() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setId(estimateId);
            estimate.setWorkOrder(entity);
            estimate.setStatus(EstimateStatus.PENDING);
            estimate.setTotalAmount(BigDecimal.valueOf(50));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            when(repository.save(entity)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(new EstimateResult(
                    estimateId, WORK_ORDER_ID, EstimateStatus.APPROVED, BigDecimal.valueOf(50), BigDecimal.ZERO,
                    BigDecimal.valueOf(50), null, null, List.of()));

            service.approveEstimate(WORK_ORDER_ID, estimateId).await().indefinitely();

            assertEquals(WorkOrderStatus.IN_PROGRESS, entity.getStatus());
            verify(repository, never()).saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class));
        }

        @Test
        @DisplayName("should throw EstimateAlreadyDecidedException when estimate is not PENDING")
        void shouldThrowWhenAlreadyDecided() {
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setStatus(EstimateStatus.APPROVED);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));

            assertThrows(EstimateAlreadyDecidedException.class,
                    () -> service.approveEstimate(WORK_ORDER_ID, estimateId).await().indefinitely());
        }

        @Test
        @DisplayName("should throw EstimateNotFoundException when estimate does not belong to the work order")
        void shouldThrowWhenEstimateMissing() {
            UUID estimateId = UUID.randomUUID();
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().nullItem());

            assertThrows(EstimateNotFoundException.class,
                    () -> service.approveEstimate(WORK_ORDER_ID, estimateId).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("rejectEstimate")
    class RejectEstimate {

        @Test
        @DisplayName("deve recusar o orçamento, concluir a OS e persistir o histórico")
        void shouldRejectAndCompleteWorkOrder() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setId(estimateId);
            estimate.setWorkOrder(entity);
            estimate.setStatus(EstimateStatus.PENDING);
            estimate.setTotalAmount(BigDecimal.valueOf(100));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(new EstimateResult(
                    estimateId, WORK_ORDER_ID, EstimateStatus.REJECTED, BigDecimal.valueOf(100), BigDecimal.ZERO,
                    BigDecimal.valueOf(100), null, null, List.of()));

            service.rejectEstimate(WORK_ORDER_ID, estimateId).await().indefinitely();

            assertEquals(EstimateStatus.REJECTED, estimate.getStatus());
            assertEquals(WorkOrderStatus.COMPLETED, entity.getStatus());
            assertNotNull(entity.getClosedAt());
            assertNotNull(entity.getCancelledAt());

            ArgumentCaptor<WorkOrderHistory> historyCaptor = ArgumentCaptor.forClass(WorkOrderHistory.class);
            verify(repository).saveWithHistory(any(WorkOrder.class), historyCaptor.capture());
            assertEquals(WorkOrderStatus.WAITING_APPROVAL, historyCaptor.getValue().getPreviousStatus());
            assertEquals(WorkOrderStatus.COMPLETED, historyCaptor.getValue().getNewStatus());
        }

        @Test
        @DisplayName("should throw EstimateAlreadyDecidedException when estimate is not PENDING")
        void shouldThrowWhenAlreadyDecided() {
            UUID estimateId = UUID.randomUUID();
            Estimate estimate = new Estimate();
            estimate.setStatus(EstimateStatus.APPROVED);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));

            assertThrows(EstimateAlreadyDecidedException.class,
                    () -> service.rejectEstimate(WORK_ORDER_ID, estimateId).await().indefinitely());
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

        private Estimate pendingEstimate() {
            Estimate estimate = new Estimate();
            estimate.setId(estimateId);
            estimate.setWorkOrder(entity);
            estimate.setStatus(EstimateStatus.PENDING);
            estimate.setTotalAmount(BigDecimal.valueOf(100));
            return estimate;
        }

        private void stubDecisionOn(Estimate estimate, EstimateStatus decidedStatus) {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(new EstimateResult(
                    estimateId, WORK_ORDER_ID, decidedStatus, BigDecimal.valueOf(100), BigDecimal.ZERO,
                    BigDecimal.valueOf(100), null, null, List.of()));
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

        @Test
        @DisplayName("deve aprovar o orçamento da própria ordem e iniciar a execução")
        void shouldApproveOwnEstimate() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Estimate estimate = pendingEstimate();
            stubDecisionOn(estimate, EstimateStatus.APPROVED);

            service.approveCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, estimateId).await().indefinitely();

            assertEquals(EstimateStatus.APPROVED, estimate.getStatus());
            assertEquals(WorkOrderStatus.IN_PROGRESS, entity.getStatus());
        }

        @Test
        @DisplayName("deve recusar o orçamento da própria ordem e concluí-la como cancelada")
        void shouldRejectOwnEstimate() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Estimate estimate = pendingEstimate();
            stubDecisionOn(estimate, EstimateStatus.REJECTED);

            service.rejectCustomerEstimate(CUSTOMER_ID, WORK_ORDER_ID, estimateId).await().indefinitely();

            assertEquals(EstimateStatus.REJECTED, estimate.getStatus());
            assertEquals(WorkOrderStatus.COMPLETED, entity.getStatus());
            assertNotNull(entity.getCancelledAt());
        }

        @Test
        @DisplayName("deve impedir que o cliente decida o orçamento de outro cliente")
        void shouldNotDecideEstimateOfAnotherCustomer() {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.approveCustomerEstimate(OTHER_CUSTOMER_ID, WORK_ORDER_ID, estimateId)
                            .await().indefinitely());
            assertThrows(WorkOrderNotFoundException.class,
                    () -> service.rejectCustomerEstimate(OTHER_CUSTOMER_ID, WORK_ORDER_ID, estimateId)
                            .await().indefinitely());
            verify(estimateRepository, never()).findByEstimateIdAndWorkOrderId(any(), any());
        }
    }

    @Nested
    @DisplayName("envio do orçamento ao entrar em WAITING_APPROVAL")
    class SendEstimateToCustomer {

        private static final UUID ESTIMATE_ID = UUID.randomUUID();

        @Test
        @DisplayName("deve reservar as peças, marcar o envio e convidar o cliente a decidir")
        void shouldReservePartsAndInviteCustomer() {
            entity.setStatus(WorkOrderStatus.DIAGNOSIS);
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN");
            Estimate estimate = pendingEstimate(part, 2);

            givenWaitingApprovalTransition(estimate);
            when(catalog.saveParts(List.of(part))).thenReturn(Uni.createFrom().voidItem());
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));

            service.updateStatus(WORK_ORDER_ID,
                    new ChangeWorkOrderStatusCommand(WorkOrderStatus.WAITING_APPROVAL)).await().indefinitely();

            assertEquals(WorkOrderStatus.WAITING_APPROVAL, entity.getStatus());
            assertEquals(3, part.getStockQuantity());
            assertNotNull(estimate.getSentAt());
            verify(notificationService).notifyEstimateAwaitingDecision(any(), any(), any());
        }

        @Test
        @DisplayName("deve emitir um token de aprovação e um de recusa, ambos válidos por sete dias")
        void shouldIssueOneTokenPerDecision() {
            entity.setStatus(WorkOrderStatus.DIAGNOSIS);
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN");
            Estimate estimate = pendingEstimate(part, 2);

            givenWaitingApprovalTransition(estimate);
            when(catalog.saveParts(List.of(part))).thenReturn(Uni.createFrom().voidItem());
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));

            service.updateStatus(WORK_ORDER_ID,
                    new ChangeWorkOrderStatusCommand(WorkOrderStatus.WAITING_APPROVAL)).await().indefinitely();

            ArgumentCaptor<EstimateDecisionToken> tokenCaptor =
                    ArgumentCaptor.forClass(EstimateDecisionToken.class);
            verify(decisionTokenRepository, times(2)).save(tokenCaptor.capture());
            List<EstimateDecisionToken> tokens = tokenCaptor.getAllValues();
            assertEquals(List.of(EstimateDecision.APPROVE, EstimateDecision.REJECT),
                    tokens.stream().map(EstimateDecisionToken::getDecision).toList());
            tokens.forEach(token -> {
                assertEquals(ESTIMATE_ID, token.getEstimateId());
                assertEquals(WORK_ORDER_ID, token.getWorkOrderId());
                assertEquals(token.getIssuedAt().plusDays(7), token.getExpiresAt());
            });

            ArgumentCaptor<EstimateDecisionInvitation> invitationCaptor =
                    ArgumentCaptor.forClass(EstimateDecisionInvitation.class);
            verify(notificationService)
                    .notifyEstimateAwaitingDecision(any(), any(), invitationCaptor.capture());
            assertEquals("signed-APPROVE", invitationCaptor.getValue().approveToken());
            assertEquals("signed-REJECT", invitationCaptor.getValue().rejectToken());
        }

        @Test
        @DisplayName("deve manter a OS em DIAGNOSIS e não notificar quando faltar saldo de peça")
        void shouldKeepDiagnosisWhenStockIsInsufficient() {
            entity.setStatus(WorkOrderStatus.DIAGNOSIS);
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 1, "UN");
            Estimate estimate = pendingEstimate(part, 5);

            givenWaitingApprovalTransition(estimate);

            assertThrows(InsufficientPartStockException.class,
                    () -> service.updateStatus(WORK_ORDER_ID,
                            new ChangeWorkOrderStatusCommand(WorkOrderStatus.WAITING_APPROVAL))
                            .await().indefinitely());

            assertEquals(1, part.getStockQuantity());
            assertNull(estimate.getSentAt());
            verify(notificationService, never()).notifyEstimateAwaitingDecision(any(), any(), any());
            verify(catalog, never()).saveParts(any());
        }

        @Test
        @DisplayName("deve falhar quando a ordem não tem orçamento pendente para enviar")
        void shouldFailWithoutPendingEstimate() {
            entity.setStatus(WorkOrderStatus.DIAGNOSIS);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findPendingByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().nullItem());

            assertThrows(EstimateNotFoundException.class,
                    () -> service.updateStatus(WORK_ORDER_ID,
                            new ChangeWorkOrderStatusCommand(WorkOrderStatus.WAITING_APPROVAL))
                            .await().indefinitely());
        }

        private void givenWaitingApprovalTransition(Estimate estimate) {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findPendingByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
            lenient().when(mapper.toResult(entity)).thenReturn(response);
        }

        private Estimate pendingEstimate(Part part, int quantity) {
            Estimate estimate = Estimate.create(entity, List.of(EstimateItem.create(part, quantity, null)),
                    List.of());
            estimate.setId(ESTIMATE_ID);
            return estimate;
        }
    }

    @Nested
    @DisplayName("decideEstimate")
    class DecideEstimate {

        private static final String SIGNED_TOKEN = "signed-token";
        private static final UUID TOKEN_ID = UUID.randomUUID();
        private static final UUID ESTIMATE_ID = UUID.randomUUID();

        @Test
        @DisplayName("deve aprovar o orçamento, iniciar a execução e manter a reserva de estoque")
        void shouldApproveAndKeepReservation() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN");
            Estimate estimate = reservedEstimate(part, 2);

            givenDecisionToken(EstimateDecision.APPROVE);
            givenWorkOrderAndEstimate(estimate);
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(estimateResult(EstimateStatus.APPROVED));

            service.decideEstimate(SIGNED_TOKEN).await().indefinitely();

            assertEquals(EstimateStatus.APPROVED, estimate.getStatus());
            assertEquals(WorkOrderStatus.IN_PROGRESS, entity.getStatus());
            assertEquals(3, part.getStockQuantity());
            verify(catalog, never()).saveParts(any());
        }

        @Test
        @DisplayName("deve recusar o orçamento, devolver as peças ao estoque e concluir a OS")
        void shouldRejectAndRestoreStock() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Part part = new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN");
            Estimate estimate = reservedEstimate(part, 2);

            givenDecisionToken(EstimateDecision.REJECT);
            givenWorkOrderAndEstimate(estimate);
            when(catalog.saveParts(List.of(part))).thenReturn(Uni.createFrom().voidItem());
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(estimateResult(EstimateStatus.REJECTED));

            service.decideEstimate(SIGNED_TOKEN).await().indefinitely();

            assertEquals(EstimateStatus.REJECTED, estimate.getStatus());
            assertEquals(WorkOrderStatus.COMPLETED, entity.getStatus());
            assertNotNull(entity.getCancelledAt());
            assertEquals(5, part.getStockQuantity());
        }

        @Test
        @DisplayName("deve marcar o token como consumido ao registrar a decisão")
        void shouldConsumeTokenOnce() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Estimate estimate = reservedEstimate(new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN"), 2);

            EstimateDecisionToken token = givenDecisionToken(EstimateDecision.APPROVE);
            givenWorkOrderAndEstimate(estimate);
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.save(estimate)).thenReturn(Uni.createFrom().item(estimate));
            when(estimateMapper.toResult(estimate)).thenReturn(estimateResult(EstimateStatus.APPROVED));

            service.decideEstimate(SIGNED_TOKEN).await().indefinitely();

            assertNotNull(token.getConsumedAt());
            verify(decisionTokenRepository).save(token);
        }

        @Test
        @DisplayName("deve recusar um link já utilizado sem tocar na OS nem no estoque")
        void shouldRejectReusedToken() {
            EstimateDecisionToken token = givenDecisionToken(EstimateDecision.APPROVE);
            token.consume(LocalDateTime.now().minusDays(1));

            assertThrows(EstimateDecisionTokenAlreadyUsedException.class,
                    () -> service.decideEstimate(SIGNED_TOKEN).await().indefinitely());

            verify(repository, never()).findByWorkOrderId(any());
            verify(catalog, never()).saveParts(any());
        }

        @Test
        @DisplayName("deve recusar um link expirado sem tocar na OS nem no estoque")
        void shouldRejectExpiredToken() {
            EstimateDecisionToken token = givenDecisionToken(EstimateDecision.REJECT);
            token.setExpiresAt(LocalDateTime.now().minusDays(1));

            assertThrows(ExpiredEstimateDecisionTokenException.class,
                    () -> service.decideEstimate(SIGNED_TOKEN).await().indefinitely());

            verify(repository, never()).findByWorkOrderId(any());
            verify(catalog, never()).saveParts(any());
        }

        @Test
        @DisplayName("deve recusar um link assinado cujo token não existe mais")
        void shouldRejectUnknownToken() {
            when(decisionTokenSignature.readTokenId(SIGNED_TOKEN)).thenReturn(TOKEN_ID);
            when(decisionTokenRepository.findByTokenId(TOKEN_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(InvalidEstimateDecisionTokenException.class,
                    () -> service.decideEstimate(SIGNED_TOKEN).await().indefinitely());
        }

        @Test
        @DisplayName("deve propagar a assinatura inválida como link inválido")
        void shouldRejectTamperedToken() {
            when(decisionTokenSignature.readTokenId(SIGNED_TOKEN))
                    .thenThrow(new InvalidEstimateDecisionTokenException());

            assertThrows(InvalidEstimateDecisionTokenException.class,
                    () -> service.decideEstimate(SIGNED_TOKEN).await().indefinitely());

            verify(decisionTokenRepository, never()).findByTokenId(any());
        }

        @Test
        @DisplayName("deve recusar a segunda decisão sobre um orçamento já decidido")
        void shouldRejectSecondDecision() {
            entity.setStatus(WorkOrderStatus.WAITING_APPROVAL);
            Estimate estimate = reservedEstimate(new Part("Filtro", "desc", BigDecimal.TEN, 5, "UN"), 2);
            estimate.approve(LocalDateTime.now());

            givenDecisionToken(EstimateDecision.REJECT);
            givenWorkOrderAndEstimate(estimate);

            assertThrows(EstimateAlreadyDecidedException.class,
                    () -> service.decideEstimate(SIGNED_TOKEN).await().indefinitely());

            verify(catalog, never()).saveParts(any());
        }

        private EstimateDecisionToken givenDecisionToken(EstimateDecision decision) {
            EstimateDecisionToken token = EstimateDecisionToken.issue(
                    WORK_ORDER_ID, ESTIMATE_ID, decision, LocalDateTime.now());
            token.setId(TOKEN_ID);
            when(decisionTokenSignature.readTokenId(SIGNED_TOKEN)).thenReturn(TOKEN_ID);
            when(decisionTokenRepository.findByTokenId(TOKEN_ID)).thenReturn(Uni.createFrom().item(token));
            return token;
        }

        private void givenWorkOrderAndEstimate(Estimate estimate) {
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.findByEstimateIdAndWorkOrderId(ESTIMATE_ID, WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(estimate));
        }

        private Estimate reservedEstimate(Part part, int quantity) {
            Estimate estimate = Estimate.create(entity, List.of(EstimateItem.create(part, quantity, null)),
                    List.of());
            estimate.setId(ESTIMATE_ID);
            estimate.reserveParts(LocalDateTime.now());
            return estimate;
        }

        private EstimateResult estimateResult(EstimateStatus status) {
            return new EstimateResult(ESTIMATE_ID, WORK_ORDER_ID, status, BigDecimal.valueOf(20),
                    BigDecimal.ZERO, BigDecimal.valueOf(20), null, null, List.of());
        }
    }

    @Nested
    @DisplayName("addService")
    class AddService {

        @Test
        @DisplayName("should persist a labor service line")
        void shouldPersistServiceLine() {
            AddWorkOrderServiceCommand request = new AddWorkOrderServiceCommand("Troca de oleo", BigDecimal.TEN, null);
            br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService lineEntity =
                    new br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService();
            WorkOrderServiceResult lineResponse = new WorkOrderServiceResult(
                    UUID.randomUUID(), WORK_ORDER_ID, "Troca de oleo", BigDecimal.TEN, null, null);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(serviceMapper.toEntity(request, entity, null)).thenReturn(lineEntity);
            when(serviceRepository.save(lineEntity)).thenReturn(Uni.createFrom().item(lineEntity));
            when(serviceMapper.toResult(lineEntity)).thenReturn(lineResponse);

            WorkOrderServiceResult result = service.addService(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals("Troca de oleo", result.description());
            verify(serviceRepository, times(1)).save(lineEntity);
        }

        @Test
        @DisplayName("should resolve and persist the referenced service item when serviceItemId is provided")
        void shouldResolveServiceItem() {
            UUID serviceItemId = UUID.randomUUID();
            ServiceItem serviceItem = new ServiceItem();
            serviceItem.setId(serviceItemId);
            AddWorkOrderServiceCommand request =
                    new AddWorkOrderServiceCommand("Troca de oleo", BigDecimal.TEN, serviceItemId);
            br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService lineEntity =
                    new br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService();
            WorkOrderServiceResult lineResponse = new WorkOrderServiceResult(
                    UUID.randomUUID(), WORK_ORDER_ID, "Troca de oleo", BigDecimal.TEN, null, serviceItemId);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(catalog.findServiceItemById(serviceItemId)).thenReturn(Uni.createFrom().item(serviceItem));
            when(serviceMapper.toEntity(request, entity, serviceItem)).thenReturn(lineEntity);
            when(serviceRepository.save(lineEntity)).thenReturn(Uni.createFrom().item(lineEntity));
            when(serviceMapper.toResult(lineEntity)).thenReturn(lineResponse);

            WorkOrderServiceResult result = service.addService(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(serviceItemId, result.serviceItemId());
        }

        @Test
        @DisplayName("should throw ServiceItemNotFoundException when referenced service item does not exist")
        void shouldThrowWhenServiceItemMissing() {
            UUID serviceItemId = UUID.randomUUID();
            AddWorkOrderServiceCommand request =
                    new AddWorkOrderServiceCommand("Troca de oleo", BigDecimal.TEN, serviceItemId);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(catalog.findServiceItemById(serviceItemId)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(ServiceItemNotFoundException.class,
                    () -> service.addService(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should throw WorkOrderLockedException when work order is delivered or cancelled")
        void shouldThrowWhenLocked() {
            entity.setStatus(WorkOrderStatus.DELIVERED);
            AddWorkOrderServiceCommand request = new AddWorkOrderServiceCommand("Troca de oleo", BigDecimal.TEN, null);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderLockedException.class,
                    () -> service.addService(WORK_ORDER_ID, request).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("should complete the work order using the approved estimate value when finalValue is omitted")
        void shouldCompleteUsingEstimatedValueWhenOmitted() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            entity.setEstimatedValue(BigDecimal.valueOf(200));
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(null);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.close(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(WorkOrderStatus.COMPLETED, entity.getStatus());
            assertEquals(BigDecimal.valueOf(200), entity.getFinalValue());
            assertNotNull(entity.getClosedAt());
        }

        @Test
        @DisplayName("should use the provided finalValue when present")
        void shouldUseProvidedFinalValue() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            entity.setEstimatedValue(BigDecimal.valueOf(200));
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(BigDecimal.valueOf(250));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.close(WORK_ORDER_ID, request).await().indefinitely();

            assertEquals(BigDecimal.valueOf(250), entity.getFinalValue());
        }

        @Test
        @DisplayName("should record history transition to COMPLETED when finalValue differs from the estimate")
        void shouldRecordHistoryWhenFinalValueDiffersFromEstimate() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            entity.setEstimatedValue(BigDecimal.valueOf(200));
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(BigDecimal.valueOf(250));

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.saveWithHistory(any(WorkOrder.class), any(WorkOrderHistory.class)))
                    .thenReturn(Uni.createFrom().item(entity));
            when(mapper.toResult(entity)).thenReturn(response);

            service.close(WORK_ORDER_ID, request).await().indefinitely();

            ArgumentCaptor<WorkOrderHistory> historyCaptor = ArgumentCaptor.forClass(WorkOrderHistory.class);
            verify(repository).saveWithHistory(any(WorkOrder.class), historyCaptor.capture());
            WorkOrderHistory history = historyCaptor.getValue();
            assertEquals(WorkOrderStatus.IN_PROGRESS, history.getPreviousStatus());
            assertEquals(WorkOrderStatus.COMPLETED, history.getNewStatus());
            assertEquals(BigDecimal.valueOf(250), entity.getFinalValue());
        }

        @Test
        @DisplayName("should throw InvalidWorkOrderStatusTransitionException when not IN_PROGRESS")
        void shouldThrowWhenNotInProgress() {
            entity.setStatus(WorkOrderStatus.DIAGNOSIS);
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(null);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(InvalidWorkOrderStatusTransitionException.class,
                    () -> service.close(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should throw EstimateNotApprovedException when there is no approved estimate")
        void shouldThrowWhenNoApprovedEstimate() {
            entity.setStatus(WorkOrderStatus.IN_PROGRESS);
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(null);

            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));
            when(estimateRepository.existsApprovedByWorkOrderId(WORK_ORDER_ID))
                    .thenReturn(Uni.createFrom().item(false));

            assertThrows(EstimateNotApprovedException.class,
                    () -> service.close(WORK_ORDER_ID, request).await().indefinitely());
        }

        @Test
        @DisplayName("should throw WorkOrderLockedException when already delivered")
        void shouldThrowWhenLocked() {
            entity.setStatus(WorkOrderStatus.DELIVERED);
            CloseWorkOrderCommand request = new CloseWorkOrderCommand(null);
            when(repository.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(Uni.createFrom().item(entity));

            assertThrows(WorkOrderLockedException.class,
                    () -> service.close(WORK_ORDER_ID, request).await().indefinitely());
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
