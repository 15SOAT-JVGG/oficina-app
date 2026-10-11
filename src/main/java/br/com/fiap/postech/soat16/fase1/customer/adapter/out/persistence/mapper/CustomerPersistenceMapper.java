package br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.mapper;

import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.entity.CustomerJpaEntity;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.shared.adapter.out.persistence.AuditPersistenceMapper;

public final class CustomerPersistenceMapper {

    private CustomerPersistenceMapper() {
    }

    public static Customer toDomain(CustomerJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        var customer = new Customer();
        customer.setId(entity.getId());
        customer.setFirstName(entity.getFirstName());
        customer.setLastName(entity.getLastName());
        customer.setEmail(entity.getEmail());
        customer.setPhoneNumber(entity.getPhoneNumber());
        customer.setDocument(entity.getDocument());
        customer.setDocumentType(entity.getDocumentType());
        customer.setActive(entity.isActive());
        AuditPersistenceMapper.copyToDomain(entity, customer);
        return customer;
    }

    public static CustomerJpaEntity toJpaEntity(Customer customer) {
        if (customer == null) {
            return null;
        }
        var entity = new CustomerJpaEntity();
        entity.setId(customer.getId());
        copyState(customer, entity);
        AuditPersistenceMapper.copyToJpaEntity(customer, entity);
        return entity;
    }

    /**
     * Referência somente com identidade, usada para preencher chaves estrangeiras sem carregar o
     * agregado completo de outro contexto.
     */
    public static CustomerJpaEntity toJpaReference(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        var entity = new CustomerJpaEntity();
        entity.setId(customerId);
        return entity;
    }

    public static void copyState(Customer source, CustomerJpaEntity target) {
        target.setFirstName(source.getFirstName());
        target.setLastName(source.getLastName());
        target.setEmail(source.getEmail());
        target.setPhoneNumber(source.getPhoneNumber());
        target.setDocument(source.getDocument());
        target.setDocumentType(source.getDocumentType());
        target.setActive(source.isActive());
    }
}
