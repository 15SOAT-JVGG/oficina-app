package br.com.fiap.postech.soat16.fase1.workorder.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.part.domain.model.Part;
import br.com.fiap.postech.soat16.fase1.servicecatalog.domain.exception.ServiceItemNotFoundException;
import br.com.fiap.postech.soat16.fase1.servicecatalog.domain.model.ServiceItem;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.exception.VehicleNotFoundException;
import br.com.fiap.postech.soat16.fase1.worker.domain.exception.WorkerNotFoundException;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.AddWorkOrderServiceCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.ChangeWorkOrderStatusCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.CloseWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.CreateEstimateCommand;
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
import br.com.fiap.postech.soat16.fase1.workorder.application.result.OpenWorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderMetricsResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderServiceResult;
import br.com.fiap.postech.soat16.fase1.workorder.application.result.WorkOrderTrackingResult;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimateNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.EstimatePartNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.InvalidEstimateDecisionTokenException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.exception.WorkOrderNotFoundException;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.Estimate;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.EstimateDecisionToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.EstimateItem;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrder;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderHistory;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderTrackingToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateDecision;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderPersistencePort repository;
    private final EstimatePersistencePort estimateRepository;
    private final WorkOrderServicePersistencePort serviceRepository;
    private final EstimateDecisionTokenPersistencePort decisionTokenRepository;
    private final WorkshopCatalogPort catalog;
    private final WorkOrderMapper mapper;
    private final EstimateMapper estimateMapper;
    private final WorkOrderServiceMapper serviceMapper;
    private final WorkOrderNotificationPort notificationService;
    private final EstimateDecisionTokenSignaturePort decisionTokenSignature;
    private final WorkOrderTrackingTokenSignaturePort trackingTokenSignature;

    /**
     * A fila operacional da oficina: somente ordens ainda em atendimento, agrupadas pelo estágio de
     * trabalho e, dentro de cada grupo, da mais antiga para a mais recente.
     */
    @WithSession
    public Uni<PagedResult<WorkOrderResult>> findOperationalQueue(int page, int size) {
        return Uni.combine().all()
                .unis(repository.findOperationalQueuePage(page, size), repository.countOperationalQueue()).asTuple()
                .map(tuple -> PagedResult.of(
                        tuple.getItem1().stream().map(mapper::toResult).toList(),
                        page,
                        size,
                        tuple.getItem2()));
    }

    @WithSession
    public Uni<WorkOrderMetricsResult> averageExecutionTime() {
        return repository.findClosed().map(orders -> {
            if (orders.isEmpty()) {
                return new WorkOrderMetricsResult(0, null);
            }
            double averageMinutes = orders.stream()
                    .mapToDouble(o -> Duration.between(o.getOpenedAt(), o.getClosedAt()).toSeconds() / 60.0)
                    .average()
                    .orElse(0);
            return new WorkOrderMetricsResult(orders.size(), Math.round(averageMinutes * 100) / 100.0);
        });
    }

    @WithSession
    public Uni<WorkOrderResult> findById(UUID id) {
        return repository.findByWorkOrderId(id)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .map(mapper::toResult);
    }

    /**
     * O acompanhamento do cliente: o link assinado é a única credencial, e ele responde apenas o
     * andamento do atendimento. Um link forjado ou vencido não conta nada sobre a ordem.
     */
    @WithSession
    public Uni<WorkOrderTrackingResult> track(String signedToken) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return Uni.createFrom().item(signedToken)
                .map(trackingTokenSignature::read)
                .invoke(token -> token.ensureValidAt(now))
                .flatMap(token -> repository.findByWorkOrderId(token.workOrderId()))
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .map(mapper::toTrackingResult);
    }

    /**
     * Abre a ordem em RECEIVED e, quando a solicitação inicial traz peças ou serviços, o orçamento
     * pendente correspondente. A transação única garante que uma referência inválida não deixe
     * ordem, linhas de serviço ou orçamento pela metade.
     */
    @WithTransaction
    public Uni<OpenWorkOrderResult> create(OpenWorkOrderCommand request) {
        LocalDateTime openedAt = LocalDateTime.now(ZoneId.systemDefault());
        return catalog.findCustomerById(request.customerId())
                .onItem().ifNull().failWith(CustomerNotFoundException::new)
                .flatMap(customer -> catalog.findVehicleById(request.vehicleId())
                        .onItem().ifNull().failWith(VehicleNotFoundException::new)
                        .flatMap(vehicle -> resolveAssignedWorker(request.assignedWorkerId())
                                .flatMap(worker -> repository.save(WorkOrder.open(
                                        customer,
                                        vehicle,
                                        worker,
                                        request.description(),
                                        request.priority(),
                                        openedAt)))))
                .flatMap(order -> openInitialEstimate(order, request, openedAt)
                        .map(estimate -> new OpenWorkOrderResult(
                                mapper.toResult(order),
                                estimateMapper.toResult(estimate)))
                        .flatMap(opened -> notifyProgress(order, openedAt).replaceWith(opened)));
    }

    private Uni<Estimate> openInitialEstimate(WorkOrder order, OpenWorkOrderCommand request,
            LocalDateTime openedAt) {
        if (!request.hasInitialRequest()) {
            return Uni.createFrom().nullItem();
        }
        return requestServices(order, request.services(), openedAt)
                .flatMap(services -> requestParts(request.parts())
                        .flatMap(items -> persistEstimate(order, items, services)))
                .flatMap(estimate -> persistOrderWithHistory(order, order.registerEstimate(estimate, openedAt))
                        .replaceWith(estimate));
    }

    /**
     * As linhas são gravadas em sequência: a sessão reativa do Hibernate não aceita escritas
     * concorrentes.
     */
    private Uni<List<br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService>> requestServices(
            WorkOrder order, List<OpenWorkOrderCommand.RequestedService> requested, LocalDateTime openedAt) {
        return Multi.createFrom().iterable(requested)
                .onItem().transformToUniAndConcatenate(service -> catalog
                        .findServiceItemById(service.serviceItemId())
                        .onItem().ifNull()
                        .failWith(() -> new ServiceItemNotFoundException(service.serviceItemId()))
                        .flatMap(serviceItem -> serviceRepository.save(
                                br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService
                                        .requestFromCatalog(order, serviceItem, openedAt))))
                .collect().asList();
    }

    private Uni<List<EstimateItem>> requestParts(List<OpenWorkOrderCommand.RequestedPart> requested) {
        if (requested.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<Uni<EstimateItem>> itemUnis = requested.stream()
                .map(part -> lookupPart(part.partId())
                        .map(found -> EstimateItem.create(found, part.quantity(), null)))
                .toList();
        return Uni.join().all(itemUnis).andFailFast();
    }

    private Uni<Part> lookupPart(UUID partId) {
        return catalog.findPartById(partId)
                .onItem().ifNull().failWith(() -> new EstimatePartNotFoundException(partId));
    }

    private Uni<Worker> resolveAssignedWorker(UUID assignedWorkerId) {
        if (assignedWorkerId == null) {
            return Uni.createFrom().nullItem();
        }
        return catalog.findWorkerById(assignedWorkerId)
                .onItem().ifNull().failWith(() -> new WorkerNotFoundException(assignedWorkerId));
    }

    /**
     * Entrar em WAITING_APPROVAL é o passo que compromete a oficina com o cliente: a mesma transação
     * reserva as peças do orçamento pendente e envia o convite de decisão. Saldo insuficiente
     * desfaz tudo e a ordem permanece em DIAGNOSIS, sem e-mail.
     */
    @WithTransaction
    public Uni<WorkOrderResult> updateStatus(UUID id, ChangeWorkOrderStatusCommand request) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return repository.findByWorkOrderId(id)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> {
                    order.ensureMutable();
                    Uni<Boolean> approvedEstimate = requiresApprovedEstimate(request.status())
                            ? hasApprovedEstimate(id)
                            : Uni.createFrom().item(false);
                    return approvedEstimate
                            .flatMap(hasApproved -> {
                                var history = order.transitionTo(request.status(), hasApproved, now);
                                return persistOrderWithHistory(order, Optional.of(history));
                            })
                            .flatMap(saved -> request.status() == WorkOrderStatus.WAITING_APPROVAL
                                    ? sendPendingEstimateToCustomer(saved, now).replaceWith(saved)
                                    : Uni.createFrom().item(saved))
                            .flatMap(saved -> notifyProgress(saved, now).replaceWith(saved))
                            .map(mapper::toResult);
                });
    }

    private Uni<Void> sendPendingEstimateToCustomer(WorkOrder order, LocalDateTime now) {
        return estimateRepository.findPendingByWorkOrderId(order.getId())
                .onItem().ifNull().failWith(EstimateNotFoundException::new)
                .flatMap(estimate -> awaitCustomerDecision(order, estimate, now));
    }

    private Uni<Void> awaitCustomerDecision(WorkOrder order, Estimate estimate, LocalDateTime now) {
        return reserveParts(estimate, now)
                .flatMap(ignored -> {
                    estimate.markSent(now);
                    return estimateRepository.save(estimate);
                })
                .flatMap(sent -> inviteCustomerToDecide(order, sent, now));
    }

    /**
     * Os dois tokens são gravados em sequência: a sessão reativa do Hibernate não aceita escritas
     * concorrentes.
     */
    private Uni<Void> inviteCustomerToDecide(WorkOrder order, Estimate estimate, LocalDateTime now) {
        var approveToken = EstimateDecisionToken.issue(
                order.getId(), estimate.getId(), EstimateDecision.APPROVE, now);
        var rejectToken = EstimateDecisionToken.issue(
                order.getId(), estimate.getId(), EstimateDecision.REJECT, now);
        return decisionTokenRepository.save(approveToken)
                .flatMap(saved -> decisionTokenRepository.save(rejectToken))
                .flatMap(saved -> notificationService.notifyEstimateAwaitingDecision(
                        order,
                        estimate,
                        new EstimateDecisionInvitation(
                                decisionTokenSignature.sign(approveToken),
                                decisionTokenSignature.sign(rejectToken),
                                approveToken.getExpiresAt())));
    }

    /**
     * Registra a decisão que o cliente tomou pelo link recebido por e-mail. O token vale uma única
     * vez: se a decisão não puder ser aplicada, a transação inteira volta atrás e o link continua
     * disponível.
     */
    @WithTransaction
    public Uni<EstimateResult> decideEstimate(String signedToken) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return Uni.createFrom().item(signedToken)
                .map(decisionTokenSignature::readTokenId)
                .flatMap(decisionTokenRepository::findByTokenId)
                .onItem().ifNull().failWith(InvalidEstimateDecisionTokenException::new)
                .flatMap(token -> {
                    token.consume(now);
                    return decisionTokenRepository.save(token);
                })
                .flatMap(token -> applyCustomerDecision(token, now))
                .map(estimateMapper::toResult);
    }

    private Uni<Estimate> applyCustomerDecision(EstimateDecisionToken token, LocalDateTime now) {
        return repository.findByWorkOrderId(token.getWorkOrderId())
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> {
                    order.ensureMutable();
                    return estimateRepository
                            .findByEstimateIdAndWorkOrderId(token.getEstimateId(), token.getWorkOrderId())
                            .onItem().ifNull().failWith(EstimateNotFoundException::new)
                            .flatMap(estimate -> token.getDecision() == EstimateDecision.APPROVE
                                    ? approve(order, estimate, now)
                                    : reject(order, estimate, now));
                });
    }

    @WithTransaction
    public Uni<EstimateResult> createEstimate(UUID workOrderId, CreateEstimateCommand request) {
        return repository.findByWorkOrderId(workOrderId)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> {
                    order.ensureMutable();
                    return buildItems(request)
                            .flatMap(items -> serviceRepository.findByWorkOrderId(workOrderId)
                                    .flatMap(services -> persistEstimate(order, items, services)))
                            .flatMap(estimate -> finalizeEstimateCreation(order, estimate));
                })
                .map(estimateMapper::toResult);
    }

    @WithTransaction
    public Uni<EstimateResult> approveEstimate(UUID workOrderId, UUID estimateId) {
        return repository.findByWorkOrderId(workOrderId)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> decide(order, workOrderId, estimateId, EstimateDecision.APPROVE))
                .map(estimateMapper::toResult);
    }

    @WithTransaction
    public Uni<EstimateResult> rejectEstimate(UUID workOrderId, UUID estimateId) {
        return repository.findByWorkOrderId(workOrderId)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> decide(order, workOrderId, estimateId, EstimateDecision.REJECT))
                .map(estimateMapper::toResult);
    }

    /**
     * As ordens do cliente autenticado pelo CPF, da mais recente para a mais antiga, incluindo as já
     * concluídas e entregues.
     */
    @WithSession
    public Uni<PagedResult<WorkOrderResult>> findCustomerWorkOrders(UUID customerId, int page, int size) {
        return Uni.combine().all()
                .unis(repository.findPageByCustomerId(customerId, page, size),
                        repository.countByCustomerId(customerId)).asTuple()
                .map(tuple -> PagedResult.of(
                        tuple.getItem1().stream().map(mapper::toResult).toList(),
                        page,
                        size,
                        tuple.getItem2()));
    }

    @WithSession
    public Uni<WorkOrderResult> findCustomerWorkOrder(UUID customerId, UUID workOrderId) {
        return findOwnedWorkOrder(customerId, workOrderId).map(mapper::toResult);
    }

    @WithTransaction
    public Uni<EstimateResult> approveCustomerEstimate(UUID customerId, UUID workOrderId, UUID estimateId) {
        return findOwnedWorkOrder(customerId, workOrderId)
                .flatMap(order -> decide(order, workOrderId, estimateId, EstimateDecision.APPROVE))
                .map(estimateMapper::toResult);
    }

    @WithTransaction
    public Uni<EstimateResult> rejectCustomerEstimate(UUID customerId, UUID workOrderId, UUID estimateId) {
        return findOwnedWorkOrder(customerId, workOrderId)
                .flatMap(order -> decide(order, workOrderId, estimateId, EstimateDecision.REJECT))
                .map(estimateMapper::toResult);
    }

    /**
     * A ordem de outro cliente responde como inexistente: um 403 confirmaria a quem tenta adivinhar
     * identificadores que aquela ordem existe.
     */
    private Uni<WorkOrder> findOwnedWorkOrder(UUID customerId, UUID workOrderId) {
        return repository.findByWorkOrderId(workOrderId)
                .map(order -> order != null && order.isOwnedBy(customerId) ? order : null)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new);
    }

    private Uni<Estimate> decide(WorkOrder order, UUID workOrderId, UUID estimateId,
            EstimateDecision decision) {
        order.ensureMutable();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return estimateRepository.findByEstimateIdAndWorkOrderId(estimateId, workOrderId)
                .onItem().ifNull().failWith(EstimateNotFoundException::new)
                .flatMap(estimate -> decision == EstimateDecision.APPROVE
                        ? approve(order, estimate, now)
                        : reject(order, estimate, now));
    }

    @WithTransaction
    public Uni<WorkOrderServiceResult> addService(UUID workOrderId, AddWorkOrderServiceCommand request) {
        return repository.findByWorkOrderId(workOrderId)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> {
                    order.ensureMutable();
                    return resolveServiceItem(request.serviceItemId())
                            .flatMap(serviceItem -> serviceRepository.save(
                                    serviceMapper.toEntity(request, order, serviceItem)));
                })
                .map(serviceMapper::toResult);
    }

    private Uni<ServiceItem> resolveServiceItem(UUID serviceItemId) {
        if (serviceItemId == null) {
            return Uni.createFrom().nullItem();
        }
        return catalog.findServiceItemById(serviceItemId)
                .onItem().ifNull().failWith(() -> new ServiceItemNotFoundException(serviceItemId));
    }

    @WithTransaction
    public Uni<WorkOrderResult> close(UUID id, CloseWorkOrderCommand request) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return repository.findByWorkOrderId(id)
                .onItem().ifNull().failWith(WorkOrderNotFoundException::new)
                .flatMap(order -> {
                    order.ensureClosable();
                    return hasApprovedEstimate(id)
                            .flatMap(hasApproved -> persistOrderWithHistory(
                                    order,
                                    Optional.of(order.close(
                                            request.finalValue(),
                                            hasApproved,
                                            now))))
                            .flatMap(saved -> notifyProgress(saved, now).replaceWith(saved))
                            .map(mapper::toResult);
                });
    }

    /**
     * A aprovação apenas confirma a reserva feita na entrada em WAITING_APPROVAL: o estoque já foi
     * baixado e permanece comprometido com a execução.
     */
    private Uni<Estimate> approve(WorkOrder order, Estimate estimate, LocalDateTime now) {
        estimate.approve(now);
        Optional<WorkOrderHistory> statusChange = order.registerEstimateApproval(estimate, now);
        return persistOrderWithHistory(order, statusChange)
                .flatMap(persisted -> estimateRepository.save(estimate))
                .flatMap(saved -> notifyProgressIfStatusChanged(order, statusChange, now).replaceWith(saved));
    }

    private Uni<Estimate> reject(WorkOrder order, Estimate estimate, LocalDateTime now) {
        estimate.reject();
        Optional<WorkOrderHistory> statusChange = order.registerEstimateRejection(now);
        return restoreParts(estimate)
                .flatMap(ignored -> persistOrderWithHistory(order, statusChange))
                .flatMap(persisted -> estimateRepository.save(estimate))
                .flatMap(saved -> notifyProgressIfStatusChanged(order, statusChange, now).replaceWith(saved));
    }

    /**
     * A condição olha o status resultante, e não se houve transição: um orçamento criado para uma
     * ordem que já estava em WAITING_APPROVAL também precisa reservar e ser enviado, senão poderia
     * ser aprovado sem que o estoque tivesse sido baixado.
     */
    private Uni<Estimate> finalizeEstimateCreation(WorkOrder order, Estimate estimate) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        Optional<WorkOrderHistory> statusChange = order.registerEstimate(estimate, now);
        return persistOrderWithHistory(order, statusChange)
                .flatMap(saved -> saved.getStatus() == WorkOrderStatus.WAITING_APPROVAL
                        ? awaitCustomerDecision(saved, estimate, now)
                        : Uni.createFrom().voidItem())
                .flatMap(ignored -> notifyProgressIfStatusChanged(order, statusChange, now))
                .replaceWith(estimate);
    }

    /**
     * O acompanhamento é reemitido a cada aviso: o cliente sempre recebe um link com trinta dias
     * pela frente, e a oficina não precisa guardar token nenhum.
     */
    private Uni<Void> notifyProgress(WorkOrder order, LocalDateTime now) {
        var token = WorkOrderTrackingToken.issue(order.getId(), now);
        return notificationService.notifyWorkOrderProgress(order, new WorkOrderTrackingInvitation(
                trackingTokenSignature.sign(token), token.expiresAt()));
    }

    private Uni<Void> notifyProgressIfStatusChanged(WorkOrder order,
            Optional<WorkOrderHistory> statusChange, LocalDateTime now) {
        return statusChange.isEmpty()
                ? Uni.createFrom().voidItem()
                : notifyProgress(order, now);
    }

    private Uni<List<EstimateItem>> buildItems(CreateEstimateCommand request) {
        List<Uni<EstimateItem>> itemUnis = request.items().stream()
                .map(itemDto -> lookupPart(itemDto.partId())
                        .map(part -> EstimateItem.create(part, itemDto.quantity(), itemDto.unitPrice())))
                .toList();
        return Uni.join().all(itemUnis).andFailFast();
    }

    private Uni<Estimate> persistEstimate(WorkOrder order, List<EstimateItem> items,
            List<br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderService> services) {
        return estimateRepository.save(Estimate.create(order, items, services));
    }

    private Uni<Void> reserveParts(Estimate estimate, LocalDateTime reservedAt) {
        List<Part> reserved = estimate.reserveParts(reservedAt);
        reserved.stream()
                .filter(Part::isLowStock)
                .forEach(part -> Log.warnf("Estoque baixo apos reserva: peca=%s restante=%d minimo=%d",
                        part.getName(), part.getStockQuantity(), part.getMinimumStock()));
        return catalog.saveParts(reserved);
    }

    private Uni<Void> restoreParts(Estimate estimate) {
        return catalog.saveParts(estimate.restoreParts());
    }

    private Uni<WorkOrder> persistOrderWithHistory(WorkOrder order,
            Optional<WorkOrderHistory> history) {
        return history
                .map(value -> repository.saveWithHistory(order, value))
                .orElseGet(() -> repository.save(order));
    }

    private Uni<Boolean> hasApprovedEstimate(UUID workOrderId) {
        return estimateRepository.existsApprovedByWorkOrderId(workOrderId)
                .map(Boolean.TRUE::equals);
    }

    private boolean requiresApprovedEstimate(WorkOrderStatus target) {
        return target == WorkOrderStatus.IN_PROGRESS;
    }
}
