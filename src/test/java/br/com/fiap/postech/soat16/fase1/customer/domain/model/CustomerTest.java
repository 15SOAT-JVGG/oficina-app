package br.com.fiap.postech.soat16.fase1.customer.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;

@DisplayName("Modelo Customer — Testes unitários")
class CustomerTest {

    @Test
    @DisplayName("deve criar o cliente com todos os dados informados")
    void shouldCreateCustomerWithAllFields() {
        Document document = Document.of("529.982.247-25");
        Customer customer = Customer.create(
                "John", "Doe", "john@example.com", "5511987654321", document);

        assertEquals("John", customer.getFirstName());
        assertEquals("Doe", customer.getLastName());
        assertEquals("john@example.com", customer.getEmail());
        assertEquals("5511987654321", customer.getPhoneNumber());
        assertEquals("52998224725", customer.getDocument());
        assertEquals(DocumentType.CPF, customer.getDocumentType());
        assertTrue(customer.isActive());
    }

    @Test
    @DisplayName("deve desativar o cliente e reativá-lo depois")
    void shouldDeactivateAndActivateCustomer() {
        Customer customer = Customer.create(
                "John", "Doe", "john@example.com", "5511987654321", Document.of("529.982.247-25"));

        customer.deactivate();
        assertFalse(customer.isActive());

        customer.activate();
        assertTrue(customer.isActive());
    }

    @Test
    @DisplayName("deve atualizar os dados editáveis sem alterar o documento")
    void shouldUpdateEditableFieldsWithoutChangingDocument() {
        Customer customer = Customer.create(
                "John",
                "Doe",
                "john@example.com",
                "5511987654321",
                Document.of("529.982.247-25"));

        customer.update("Jane", "Smith", "jane@example.com", "11999999999");

        assertEquals("Jane", customer.getFirstName());
        assertEquals("Smith", customer.getLastName());
        assertEquals("jane@example.com", customer.getEmail());
        assertEquals("11999999999", customer.getPhoneNumber());
        assertEquals("52998224725", customer.getDocument());
        assertEquals(DocumentType.CPF, customer.getDocumentType());
    }

    @Test
    @DisplayName("deve considerar iguais os clientes com o mesmo identificador")
    void shouldCompareCustomersById() {
        UUID id = UUID.randomUUID();
        Customer first = customer(id, "John");
        Customer sameId = customer(id, "Jane");
        Customer differentId = customer(UUID.randomUUID(), "John");

        assertEquals(first, sameId);
        assertNotEquals(first, differentId);
    }

    private Customer customer(UUID id, String firstName) {
        return new Customer(
                id,
                firstName,
                "Doe",
                "customer@example.com",
                "11999999999",
                "52998224725",
                DocumentType.CPF,
                true);
    }
}
