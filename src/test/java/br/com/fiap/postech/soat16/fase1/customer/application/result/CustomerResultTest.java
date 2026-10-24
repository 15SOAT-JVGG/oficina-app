package br.com.fiap.postech.soat16.fase1.customer.application.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;

@DisplayName("CustomerResult — Testes unitários")
class CustomerResultTest {

    @Test
    @DisplayName("deve mapear todos os campos do domínio")
    void shouldMapAllDomainFields() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        Customer customer = new Customer(
                id,
                "John",
                "Doe",
                "john@example.com",
                "5511987654321",
                "52998224725",
                DocumentType.CPF,
                true);
        customer.setCreatedAt(createdAt);

        CustomerResult result = CustomerResult.from(customer);

        assertEquals(id, result.customerId());
        assertEquals("John", result.firstName());
        assertEquals("Doe", result.lastName());
        assertEquals("john@example.com", result.email());
        assertEquals("5511987654321", result.phoneNumber());
        assertEquals("52998224725", result.document());
        assertEquals("CPF", result.documentType());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    @DisplayName("deve retornar nulo quando o domínio for nulo")
    void shouldReturnNullWhenDomainIsNull() {
        assertNull(CustomerResult.from(null));
    }
}
