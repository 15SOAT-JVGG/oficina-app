package br.com.fiap.postech.soat16.fase1.customer.application.result;

import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;

public record CustomerResult(
        UUID customerId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String document,
        String documentType,
        boolean active,
        OffsetDateTime createdAt
) {

    public static CustomerResult from(Customer customer) {
        if (customer == null) {
            return null;
        }
        return new CustomerResult(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getDocument(),
                customer.getDocumentType() != null ? customer.getDocumentType().name() : null,
                customer.isActive(),
                customer.getCreatedAt());
    }
}
