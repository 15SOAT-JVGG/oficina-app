package br.com.fiap.postech.soat16.fase1.workorder.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.exception.VehicleNotFoundException;
import br.com.fiap.postech.soat16.fase1.worker.domain.exception.WorkerNotFoundException;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;
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
import br.com.fiap.postech.soat16.fase1.workorder.application.result.OpenWorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderMetricsResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderTrackingToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration.EstimateProjection;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class WorkOrderService {
    private final WorkOrderPersistencePort repository;
    private final WorkOrderEventJournalPort eventJournal;
    private final EstimateProgressPort estimateProgress;
    private final WorkOrderParticipantLookupPort participants;
    private final WorkOrderMapper mapper;
    private final WorkOrderNotificationPort notificationService;
    private final WorkOrderTrackingTokenSignaturePort trackingTokenSignature;

    @WithSession
    public Uni<PagedResult<WorkOrderResult>> findOperationalQueue(int page, int size) {
        return Uni.combine().all()
                .unis(repository.findOperationalQueuePage(page, size), repository.countOperationalQueue()).asTuple()
                .map(tuple -> PagedResult.of(tuple.getItem1().stream().map(mapper::toResult).toList(),
                        page, size, tuple.getItem2()));
    }

    @WithSession
    public Uni<WorkOrderMetricsResult> averageExecutionTime() {
        return repository.findClosed().map(orders -> {
            if (orders.isEmpty()) {
                return new WorkOrderMetricsResult(0, null);
            }
            double averageMinutes = orders.stream()
                    .mapToDouble(order -> Duration.between(order.getOpenedAt(), order.getClosedAt()).toSeconds() / 60.0)
                    .average().orElse(0);
            return new WorkOrderMetricsResult(orders.size(), Math.round(averageMinutes * 100) / 100.0);
        });
    }

    @WithSession
    public Uni<WorkOrderResult> findById(UUID id) {
        return repository.findByWorkOrderId(id).onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(this::resultWithEstimateProgress);
    }

    @WithSession
    public Uni<WorkOrderTrackingResult> track(String signedToken) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return Uni.createFrom().item(signedToken).map(trackingTokenSignature::read)
                .invoke(token -> token.ensureValidAt(now))
                .flatMap(token -> repository.findByWorkOrderId(token.workOrderId()))
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new).map(mapper::toTrackingResult);
    }

    @WithTransaction
    public Uni<OpenWorkOrderResult> create(OpenWorkOrderCommand request) {
        LocalDateTime openedAt = LocalDateTime.now(ZoneId.systemDefault());
        return participants.findCustomerById(request.customerId()).onItem().ifNull().failWith(CustomerNotFoundException::new)
                .flatMap(customer -> participants.findVehicleById(request.vehicleId())
                        .onItem().ifNull().failWith(VehicleNotFoundException::new)
                        .flatMap(vehicle -> resolveAssignedWorker(request.assignedWorkerId())
                                .flatMap(worker -> repository.save(WorkOrder.open(customer, vehicle, worker,
                                        request.description(), request.priority(), openedAt)))))
                .flatMap(order -> eventJournal.recordOpening(order.getId(), request)
                        .chain(() -> notifyProgress(order, openedAt))
                        .replaceWith(() -> new OpenWorkOrderResult(request.hasInitialRequest()
                                ? mapper.toResult(order).withEstimateProgress(EstimateProjection.Status.AGUARDANDO, null)
                                : mapper.toResult(order))));
    }

    @WithTransaction
    public Uni<WorkOrderResult> updateStatus(UUID id, ChangeWorkOrderStatusCommand request) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return repository.findByWorkOrderId(id).onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> repository.saveWithHistory(order, order.transitionTo(request.status(), false, now)))
                .flatMap(saved -> notifyProgress(saved, now).replaceWith(saved)).map(mapper::toResult);
    }

    @WithSession
    public Uni<PagedResult<WorkOrderResult>> findCustomerWorkOrders(UUID customerId, int page, int size) {
        return Uni.combine().all().unis(repository.findPageByCustomerId(customerId, page, size),
                repository.countByCustomerId(customerId)).asTuple()
                .map(tuple -> PagedResult.of(tuple.getItem1().stream().map(mapper::toResult).toList(),
                        page, size, tuple.getItem2()));
    }

    @WithSession
    public Uni<WorkOrderResult> findCustomerWorkOrder(UUID customerId, UUID workOrderId) {
        return repository.findByWorkOrderId(workOrderId)
                .map(order -> order != null && order.isOwnedBy(customerId) ? order : null)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new).flatMap(this::resultWithEstimateProgress);
    }

    private Uni<WorkOrderResult> resultWithEstimateProgress(WorkOrder order) {
        return estimateProgress.findProgress(order.getId()).map(progress -> progress == null ? mapper.toResult(order)
                : mapper.toResult(order).withEstimateProgress(progress.status(), progress.failureReason()));
    }

    private Uni<Worker> resolveAssignedWorker(UUID workerId) {
        if (workerId == null) {
            return Uni.createFrom().nullItem();
        }
        return participants.findWorkerById(workerId).onItem().ifNull()
                .failWith(() -> new WorkerNotFoundException(workerId));
    }

    private Uni<Void> notifyProgress(WorkOrder order, LocalDateTime now) {
        var token = WorkOrderTrackingToken.issue(order.getId(), now);
        return notificationService.notifyWorkOrderProgress(order,
                new WorkOrderTrackingInvitation(trackingTokenSignature.sign(token), token.expiresAt()));
    }
}
