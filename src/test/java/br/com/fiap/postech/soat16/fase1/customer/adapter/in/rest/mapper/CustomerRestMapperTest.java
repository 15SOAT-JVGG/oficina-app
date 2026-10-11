package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.request.CustomerRequestDto;
import br.com.fiap.postech.soat16.fase1.customer.application.result.CustomerResult;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;

@DisplayName("CustomerRestMapper — Testes unitários")
class CustomerRestMapperTest {

    @Test
    @DisplayName("deve mapear todos os campos da requisição para o comando de criação")
    void shouldMapAllFieldsToCreateCommand() {
        CustomerRequestDto request = new CustomerRequestDto(
                "John", "Doe", "john@example.com", "11999999999", "529.982.247-25");

        var create = CustomerRestMapper.toCreateCommand(request);

        assertEquals(request.firstName(), create.firstName());
        assertEquals(request.lastName(), create.lastName());
        assertEquals(request.email(), create.email());
        assertEquals(request.phoneNumber(), create.phoneNumber());
        assertEquals(request.document(), create.document());
    }

    @Test
    @DisplayName("deve mapear os campos editáveis para o comando de atualização")
    void shouldMapEditableFieldsToUpdateCommand() {
        UUID id = UUID.randomUUID();
        CustomerRequestDto request = new CustomerRequestDto(
                "John", "Doe", "john@example.com", "11999999999", "529.982.247-25");

        var update = CustomerRestMapper.toUpdateCommand(id, request);

        assertEquals(id, update.id());
        assertEquals(request.firstName(), update.firstName());
        assertEquals(request.lastName(), update.lastName());
        assertEquals(request.email(), update.email());
        assertEquals(request.phoneNumber(), update.phoneNumber());
    }

    @Test
    @DisplayName("deve mapear todos os campos do resultado para a resposta REST")
    void shouldMapAllFieldsToRestResponse() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        CustomerResult result = new CustomerResult(
                id,
                "John",
                "Doe",
                "john@example.com",
                "11999999999",
                "52998224725",
                "CPF",
                true,
                createdAt);

        var response = CustomerRestMapper.toResponse(result);

        assertEquals(id, response.customerId());
        assertEquals("John", response.firstName());
        assertEquals("Doe", response.lastName());
        assertEquals("john@example.com", response.email());
        assertEquals("11999999999", response.phoneNumber());
        assertEquals("52998224725", response.document());
        assertEquals("CPF", response.documentType());
        assertEquals(createdAt, response.createdAt());
    }

    @Test
    @DisplayName("deve mapear conteúdo e metadados da página")
    void shouldMapPageContentAndMetadata() {
        CustomerResult result = new CustomerResult(
                UUID.randomUUID(),
                "John",
                "Doe",
                "john@example.com",
                "11999999999",
                "52998224725",
                "CPF",
                true,
                null);

        var page = CustomerRestMapper.toResponse(PagedResult.of(List.of(result), 1, 2, 5));

        assertEquals(1, page.content().size());
        assertEquals(1, page.pagination().page());
        assertEquals(2, page.pagination().size());
        assertEquals(5L, page.pagination().totalElements());
        assertEquals(3, page.pagination().totalPages());
        assertEquals(true, page.pagination().hasPrevious());
        assertEquals(true, page.pagination().hasNext());
    }

    @Test
    @DisplayName("deve retornar nulo quando o resultado for nulo")
    void shouldReturnNullWhenResultIsNull() {
        assertNull(CustomerRestMapper.toResponse((CustomerResult) null));
    }
}
