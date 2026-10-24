package br.com.fiap.postech.soat16.fase1.workorder.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.shared.domain.model.audit.AuditableEntity;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateNotApprovedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidWorkOrderStatusTransitionException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderLockedException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class WorkOrder extends AuditableEntity {

    private static final Map<WorkOrderStatus, WorkOrderStatus> FORWARD_TRANSITIONS = Map.of(
            WorkOrderStatus.RECEIVED, WorkOrderStatus.DIAGNOSIS,
            WorkOrderStatus.DIAGNOSIS, WorkOrderStatus.WAITING_APPROVAL,
            WorkOrderStatus.WAITING_APPROVAL, WorkOrderStatus.IN_PROGRESS
    );

    @EqualsAndHashCode.Include
    private UUID id;

    private Customer customer;

    private Vehicle vehicle;

    private Worker assignedWorker;

    private String description;

    private WorkOrderPriority priority;

    private WorkOrderStatus status;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    private LocalDateTime cancelledAt;

    private BigDecimal estimatedValue;

    private BigDecimal finalValue;

    public static WorkOrder open(Customer customer, Vehicle vehicle, Worker assignedWorker,
            String description, WorkOrderPriority priority, LocalDateTime openedAt) {
        var workOrder = new WorkOrder();
        workOrder.customer = customer;
        workOrder.vehicle = vehicle;
        workOrder.assignedWorker = assignedWorker;
        workOrder.description = description;
        workOrder.priority = priority != null ? priority : WorkOrderPriority.MEDIUM;
        workOrder.status = WorkOrderStatus.RECEIVED;
        workOrder.openedAt = openedAt;
        return workOrder;
    }

    public WorkOrderHistory transitionTo(WorkOrderStatus target, boolean hasApprovedEstimate,
            LocalDateTime changedAt) {
        ensureMutable();
        validateGenericTransition(target);
        ensureApprovedEstimateWhenRequired(target, hasApprovedEstimate);
        return applyStatus(target, changedAt);
    }

    public WorkOrderHistory close(BigDecimal requestedFinalValue, boolean hasApprovedEstimate,
            LocalDateTime changedAt) {
        ensureClosable();
        ensureApprovedEstimateWhenRequired(WorkOrderStatus.IN_PROGRESS, hasApprovedEstimate);
        finalValue = requestedFinalValue != null ? requestedFinalValue : estimatedValue;
        closedAt = changedAt;
        return applyStatus(WorkOrderStatus.COMPLETED, changedAt);
    }

    public Optional<WorkOrderHistory> registerEstimate(Estimate estimate, LocalDateTime changedAt) {
        ensureMutable();
        estimatedValue = estimate.getTotalAmount();
        if (status == WorkOrderStatus.DIAGNOSIS) {
            return Optional.of(applyStatus(WorkOrderStatus.WAITING_APPROVAL, changedAt));
        }
        return Optional.empty();
    }

    public Optional<WorkOrderHistory> registerEstimateApproval(Estimate estimate,
            LocalDateTime changedAt) {
        ensureMutable();
        estimatedValue = estimate.getTotalAmount();
        if (status == WorkOrderStatus.WAITING_APPROVAL) {
            return Optional.of(applyStatus(WorkOrderStatus.IN_PROGRESS, changedAt));
        }
        return Optional.empty();
    }

    public Optional<WorkOrderHistory> registerEstimateRejection(LocalDateTime changedAt) {
        ensureMutable();
        if (status == WorkOrderStatus.WAITING_APPROVAL) {
            closedAt = changedAt;
            cancelledAt = changedAt;
            return Optional.of(applyStatus(WorkOrderStatus.COMPLETED, changedAt));
        }
        return Optional.empty();
    }

    public boolean isOwnedBy(UUID customerId) {
        return customerId != null && customer != null && customerId.equals(customer.getId());
    }

    public boolean hasReservedStock() {
        return status == WorkOrderStatus.IN_PROGRESS;
    }

    /**
     * Cancelamento aqui é só um: a recusa do orçamento pelo cliente, que conclui a ordem.
     */
    public boolean wasCancelled() {
        return cancelledAt != null;
    }

    public void ensureMutable() {
        if (status == WorkOrderStatus.DELIVERED || wasCancelled()) {
            throw new WorkOrderLockedException();
        }
    }

    public void ensureClosable() {
        ensureMutable();
        if (status != WorkOrderStatus.IN_PROGRESS) {
            throw new InvalidWorkOrderStatusTransitionException(
                    "Only work orders IN_PROGRESS can be closed");
        }
    }

    private void validateGenericTransition(WorkOrderStatus target) {
        if (target == WorkOrderStatus.COMPLETED) {
            throw new InvalidWorkOrderStatusTransitionException(
                    "Use PATCH /v1/work-orders/{id}/close to complete a work order");
        }
        if (target == WorkOrderStatus.DELIVERED) {
            if (status != WorkOrderStatus.COMPLETED) {
                throw new InvalidWorkOrderStatusTransitionException(status, target);
            }
            return;
        }
        if (target != FORWARD_TRANSITIONS.get(status)) {
            throw new InvalidWorkOrderStatusTransitionException(status, target);
        }
    }

    private void ensureApprovedEstimateWhenRequired(WorkOrderStatus target,
            boolean hasApprovedEstimate) {
        if (target == WorkOrderStatus.IN_PROGRESS && !hasApprovedEstimate) {
            throw new EstimateNotApprovedException();
        }
    }

    private WorkOrderHistory applyStatus(WorkOrderStatus target, LocalDateTime changedAt) {
        var history = new WorkOrderHistory();
        history.setWorkOrder(this);
        history.setPreviousStatus(status);
        history.setNewStatus(target);
        history.setChangedAt(changedAt);
        status = target;
        return history;
    }
}
