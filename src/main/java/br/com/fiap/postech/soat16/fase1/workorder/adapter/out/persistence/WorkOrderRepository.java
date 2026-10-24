package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence.entity.WorkOrderJpaEntity;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence.mapper.WorkOrderPersistenceMapper;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderPersistencePort;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderHistory;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class WorkOrderRepository
        implements PanacheRepositoryBase<WorkOrderJpaEntity, UUID>, WorkOrderPersistencePort {

    private static final String OPERATIONAL_QUEUE_FILTER = "status in ?1";

    private static final String OPERATIONAL_QUEUE_QUERY = buildOperationalQueueQuery();

    private static final String CUSTOMER_FILTER = "customer.id = ?1";

    private final WorkOrderHistoryRepository historyRepository;

    @Override
    public Uni<List<WorkOrder>> findOperationalQueuePage(int page, int size) {
        return find(OPERATIONAL_QUEUE_QUERY, WorkOrderStatus.operationalQueue()).page(page, size).list()
                .map(entities -> entities.stream()
                        .map(WorkOrderPersistenceMapper::toDomain)
                        .toList());
    }

    @Override
    public Uni<Long> countOperationalQueue() {
        return count(OPERATIONAL_QUEUE_FILTER, WorkOrderStatus.operationalQueue());
    }

    /**
     * Traduz a fila operacional do domínio para HQL: o CASE reproduz a ordem dos status e o
     * desempate leva a ordem aberta há mais tempo para o topo. O id fecha a ordenação para que
     * ordens abertas no mesmo instante não troquem de lugar entre uma página e outra.
     */
    private static String buildOperationalQueueQuery() {
        var query = new StringBuilder(OPERATIONAL_QUEUE_FILTER).append(" ORDER BY CASE status ");
        List<WorkOrderStatus> queue = WorkOrderStatus.operationalQueue();
        for (int position = 0; position < queue.size(); position++) {
            query.append("WHEN ").append(WorkOrderStatus.class.getName()).append('.').append(queue.get(position))
                    .append(" THEN ").append(position).append(' ');
        }
        return query.append("END, openedAt ASC, id ASC").toString();
    }

    @Override
    public Uni<WorkOrder> findByWorkOrderId(UUID id) {
        return find("id = ?1", id).firstResult()
                .invoke(found -> Log.infof("WorkOrder lookup: id=%s found=%b", id, found != null))
                .map(WorkOrderPersistenceMapper::toDomain);
    }

    @Override
    public Uni<List<WorkOrder>> findPageByCustomerId(UUID customerId, int page, int size) {
        return find(CUSTOMER_FILTER + " ORDER BY openedAt DESC, id ASC", customerId).page(page, size).list()
                .map(entities -> entities.stream()
                        .map(WorkOrderPersistenceMapper::toDomain)
                        .toList());
    }

    @Override
    public Uni<Long> countByCustomerId(UUID customerId) {
        return count(CUSTOMER_FILTER, customerId);
    }

    @Override
    public Uni<List<WorkOrder>> findClosed() {
        return list("openedAt is not null and closedAt is not null")
                .map(entities -> entities.stream()
                        .map(WorkOrderPersistenceMapper::toDomain)
                        .toList());
    }

    @Override
    public Uni<WorkOrder> save(WorkOrder workOrder) {
        return upsert(workOrder).replaceWith(workOrder);
    }

    @Override
    public Uni<WorkOrder> saveWithHistory(WorkOrder workOrder, WorkOrderHistory history) {
        return historyRepository.save(history)
                .flatMap(ignored -> upsert(workOrder))
                .replaceWith(workOrder);
    }

    private Uni<WorkOrderJpaEntity> upsert(WorkOrder workOrder) {
        if (workOrder.getId() == null) {
            return persist(WorkOrderPersistenceMapper.toJpaEntity(workOrder))
                    .invoke(entity -> WorkOrderPersistenceMapper.copyGeneratedState(entity, workOrder));
        }
        return findById(workOrder.getId())
                .onItem().ifNotNull()
                .invoke(entity -> WorkOrderPersistenceMapper.copyState(workOrder, entity));
    }
}
