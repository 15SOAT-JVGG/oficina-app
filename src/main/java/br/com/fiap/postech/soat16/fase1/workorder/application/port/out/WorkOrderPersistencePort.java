package br.com.fiap.postech.soat16.fase1.workorder.application.port.out;

import java.util.List;
import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderHistory;

import io.smallrye.mutiny.Uni;

public interface WorkOrderPersistencePort {

    Uni<List<WorkOrder>> findOperationalQueuePage(int page, int size);

    Uni<Long> countOperationalQueue();

    Uni<List<WorkOrder>> findPageByCustomerId(UUID customerId, int page, int size);

    Uni<Long> countByCustomerId(UUID customerId);

    Uni<List<WorkOrder>> findClosed();

    Uni<WorkOrder> findByWorkOrderId(UUID id);

    Uni<WorkOrder> save(WorkOrder workOrder);

    Uni<WorkOrder> saveWithHistory(WorkOrder workOrder, WorkOrderHistory history);
}
