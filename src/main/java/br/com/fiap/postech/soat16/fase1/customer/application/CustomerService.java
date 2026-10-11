package br.com.fiap.postech.soat16.fase1.customer.application;

import static java.lang.Boolean.TRUE;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import br.com.fiap.postech.soat16.fase1.customer.application.command.CreateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.command.UpdateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.port.out.CustomerPersistencePort;
import br.com.fiap.postech.soat16.fase1.customer.application.port.out.CustomerVehicleLookupPort;
import br.com.fiap.postech.soat16.fase1.customer.application.result.CustomerResult;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerHasVehiclesException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.DuplicateDocumentException;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Document;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerPersistencePort repository;
    private final CustomerVehicleLookupPort vehicleLookup;

    @WithSession
    public Uni<PagedResult<CustomerResult>> findAll(String q, int page, int size) {
        return Uni.combine().all().unis(repository.findPage(page, size), repository.countCustomers()).asTuple()
                .map(tuple -> PagedResult.of(
                        tuple.getItem1().stream().map(CustomerResult::from).toList(),
                        page,
                        size,
                        tuple.getItem2()));
    }

    @WithSession
    public Uni<CustomerResult> findById(UUID id) {
        return repository.findByCustomerId(id)
                .onItem().ifNull().failWith(CustomerNotFoundException::new)
                .map(CustomerResult::from);
    }

    @WithTransaction
    public Uni<Void> create(CreateCustomerCommand command) {
        Document document = Document.of(command.document());

        return repository.existsByDocument(document.getValue())
                .flatMap(documentExists -> {
                    if (TRUE.equals(documentExists)) {
                        return Uni.createFrom().<Void>failure(new DuplicateDocumentException());
                    }
                    Customer customer = Customer.create(
                            command.firstName(),
                            command.lastName(),
                            command.email(),
                            command.phoneNumber(),
                            document);
                    return repository.save(customer).replaceWithVoid();
                });
    }

    @WithSession
    public Uni<CustomerResult> findByDocument(String rawDocument) {
        Document document = Document.of(rawDocument);
        return repository.findByDocument(document.getValue())
                .onItem().ifNull().failWith(() -> new CustomerNotFoundException(rawDocument))
                .map(CustomerResult::from);
    }

    @WithTransaction
    public Uni<CustomerResult> update(UpdateCustomerCommand command) {
        return repository.findByCustomerId(command.id())
                .onItem().ifNull().failWith(CustomerNotFoundException::new)
                .flatMap(customer -> {
                    customer.update(
                            command.firstName(),
                            command.lastName(),
                            command.email(),
                            command.phoneNumber());
                    return repository.save(customer);
                })
                .map(CustomerResult::from);
    }

    @WithTransaction
    public Uni<CustomerResult> activate(UUID id) {
        return repository.findByCustomerId(id)
                .onItem().ifNull().failWith(CustomerNotFoundException::new)
                .flatMap(customer -> {
                    customer.activate();
                    return repository.save(customer);
                })
                .map(CustomerResult::from);
    }

    @WithTransaction
    public Uni<CustomerResult> deactivate(UUID id) {
        return repository.findByCustomerId(id)
                .onItem().ifNull().failWith(CustomerNotFoundException::new)
                .flatMap(customer -> {
                    customer.deactivate();
                    return repository.save(customer);
                })
                .map(CustomerResult::from);
    }

    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return vehicleLookup.existsByCustomerId(id)
                .flatMap(hasVehicles -> {
                    if (TRUE.equals(hasVehicles)) {
                        return Uni.createFrom().<Long>failure(new CustomerHasVehiclesException());
                    }
                    return repository.deleteByCustomerId(id);
                })
                .flatMap(deleted -> {
                    if (deleted == 0) {
                        return Uni.createFrom().<Void>failure(new CustomerNotFoundException());
                    }
                    return Uni.createFrom().voidItem();
                });
    }
}
