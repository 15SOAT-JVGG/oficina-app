package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.request.CustomerRequestDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.response.CustomerResponseDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.mapper.CustomerRestMapper;
import br.com.fiap.postech.soat16.fase1.customer.application.CustomerService;
import br.com.fiap.postech.soat16.fase1.customer.application.result.CustomerResult;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.DuplicateDocumentException;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableRequestDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerController — Testes unitários")
class CustomerControllerTest {

    private static final UUID CUSTOMER_ID =
            UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Mock
    CustomerService service;

    private CustomerController controller;
    private CustomerResult customer;

    @BeforeEach
    void setUp() {
        controller = new CustomerController(service);
        customer = new CustomerResult(
                CUSTOMER_ID,
                "John",
                "Doe",
                "john.doe@example.com",
                "5511987654321",
                "52998224725",
                "CPF",
                true,
                null);
    }

    @Nested
    @DisplayName("GET /v1/customer — findAll")
    class FindAll {

        @Test
        @DisplayName("deve retornar uma página quando houver clientes")
        void shouldReturnPageWhenCustomersExist() {
            PageableRequestDto pageable = pageable();
            when(service.findAll("ignored", 0, 10))
                    .thenReturn(Uni.createFrom().item(
                            PagedResult.of(List.of(customer), 0, 10, 1)));

            PageableResponseDto<CustomerResponseDto> result =
                    controller.findAll(pageable).await().indefinitely();

            assertNotNull(result);
            assertEquals(1, result.content().size());
            assertEquals("John", result.content().getFirst().firstName());
            verify(service).findAll("ignored", 0, 10);
        }

        @Test
        @DisplayName("deve retornar uma página vazia quando não houver clientes")
        void shouldReturnEmptyPageWhenNoCustomersExist() {
            PageableRequestDto pageable = pageable();
            when(service.findAll("ignored", 0, 10))
                    .thenReturn(Uni.createFrom().item(PagedResult.of(List.of(), 0, 10, 0)));

            PageableResponseDto<CustomerResponseDto> result =
                    controller.findAll(pageable).await().indefinitely();

            assertTrue(result.content().isEmpty());
            assertEquals(0L, result.pagination().totalElements());
        }

        @Test
        @DisplayName("deve propagar falhas do serviço")
        void shouldPropagateServiceFailure() {
            PageableRequestDto pageable = pageable();
            when(service.findAll("ignored", 0, 10))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Falha no banco")));

            assertThrows(
                    RuntimeException.class,
                    () -> controller.findAll(pageable).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("GET /v1/customer/{id} — findById")
    class FindById {

        @Test
        @DisplayName("deve retornar o cliente quando encontrado")
        void shouldReturnCustomerWhenFound() {
            when(service.findById(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));

            CustomerResponseDto result =
                    controller.findById(CUSTOMER_ID).await().indefinitely();

            assertNotNull(result);
            assertEquals("John", result.firstName());
            verify(service).findById(CUSTOMER_ID);
        }

        @Test
        @DisplayName("deve propagar CustomerNotFoundException")
        void shouldPropagateNotFoundException() {
            when(service.findById(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().failure(new CustomerNotFoundException()));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> controller.findById(CUSTOMER_ID).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("GET /v1/customer/by-document/{document} — findByDocument")
    class FindByDocument {

        @Test
        @DisplayName("deve retornar o cliente quando o documento for encontrado")
        void shouldReturnCustomerWhenDocumentIsFound() {
            when(service.findByDocument("52998224725"))
                    .thenReturn(Uni.createFrom().item(customer));

            CustomerResponseDto result =
                    controller.findByDocument("52998224725").await().indefinitely();

            assertNotNull(result);
            assertEquals("John", result.firstName());
            verify(service).findByDocument("52998224725");
        }

        @Test
        @DisplayName("deve propagar CustomerNotFoundException")
        void shouldPropagateNotFoundException() {
            when(service.findByDocument("52998224725"))
                    .thenReturn(Uni.createFrom().failure(new CustomerNotFoundException()));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> controller.findByDocument("52998224725").await().indefinitely());
        }
    }

    @Nested
    @DisplayName("POST /v1/customer — create")
    class Create {

        @Test
        @DisplayName("deve retornar HTTP 201 quando o cliente for criado")
        void shouldReturnCreatedWhenCustomerIsCreated() {
            CustomerRequestDto request = request();
            when(service.create(CustomerRestMapper.toCreateCommand(request)))
                    .thenReturn(Uni.createFrom().voidItem());

            Response result = controller.create(request).await().indefinitely();

            assertEquals(201, result.getStatus());
            verify(service).create(CustomerRestMapper.toCreateCommand(request));
        }

        @Test
        @DisplayName("deve propagar DuplicateDocumentException")
        void shouldPropagateDuplicateDocumentException() {
            CustomerRequestDto request = request();
            when(service.create(CustomerRestMapper.toCreateCommand(request)))
                    .thenReturn(Uni.createFrom().failure(new DuplicateDocumentException()));

            assertThrows(
                    DuplicateDocumentException.class,
                    () -> controller.create(request).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("PUT /v1/customer/{id} — update")
    class Update {

        @Test
        @DisplayName("deve retornar HTTP 200 com o cliente atualizado")
        void shouldReturnOkWithUpdatedCustomer() {
            CustomerRequestDto request = request();
            CustomerResult updated = new CustomerResult(
                    CUSTOMER_ID,
                    "Jane",
                    "Doe",
                    "jane@example.com",
                    "5511987654321",
                    "52998224725",
                    "CPF",
                    true,
                    OffsetDateTime.now());
            when(service.update(CustomerRestMapper.toUpdateCommand(CUSTOMER_ID, request)))
                    .thenReturn(Uni.createFrom().item(updated));

            Response result = controller.update(CUSTOMER_ID, request).await().indefinitely();

            assertEquals(200, result.getStatus());
            assertEquals("Jane", ((CustomerResponseDto) result.getEntity()).firstName());
            verify(service).update(CustomerRestMapper.toUpdateCommand(CUSTOMER_ID, request));
        }

        @Test
        @DisplayName("deve propagar CustomerNotFoundException")
        void shouldPropagateNotFoundException() {
            CustomerRequestDto request = request();
            when(service.update(CustomerRestMapper.toUpdateCommand(CUSTOMER_ID, request)))
                    .thenReturn(Uni.createFrom().failure(new CustomerNotFoundException()));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> controller.update(CUSTOMER_ID, request).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("PATCH /v1/customer/{id}/activate e /deactivate — status")
    class Status {

        @Test
        @DisplayName("deve devolver o cliente desativado")
        void shouldReturnDeactivatedCustomer() {
            CustomerResult deactivated = new CustomerResult(CUSTOMER_ID, "John", "Doe", null, "5511987654321",
                    "52998224725", "CPF", false, null);
            when(service.deactivate(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(deactivated));

            CustomerResponseDto result = controller.deactivate(CUSTOMER_ID).await().indefinitely();

            assertFalse(result.active());
            verify(service).deactivate(CUSTOMER_ID);
        }

        @Test
        @DisplayName("deve devolver o cliente reativado")
        void shouldReturnActivatedCustomer() {
            when(service.activate(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));

            CustomerResponseDto result = controller.activate(CUSTOMER_ID).await().indefinitely();

            assertTrue(result.active());
            verify(service).activate(CUSTOMER_ID);
        }

        @Test
        @DisplayName("deve propagar CustomerNotFoundException")
        void shouldPropagateNotFoundException() {
            when(service.deactivate(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().failure(new CustomerNotFoundException()));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> controller.deactivate(CUSTOMER_ID).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("DELETE /v1/customer/{id} — delete")
    class Delete {

        @Test
        @DisplayName("deve retornar HTTP 204 quando o cliente for excluído")
        void shouldReturnNoContentWhenCustomerIsDeleted() {
            when(service.delete(CUSTOMER_ID)).thenReturn(Uni.createFrom().voidItem());

            Response result = controller.delete(CUSTOMER_ID).await().indefinitely();

            assertEquals(204, result.getStatus());
            verify(service).delete(CUSTOMER_ID);
        }

        @Test
        @DisplayName("deve propagar CustomerNotFoundException")
        void shouldPropagateNotFoundException() {
            when(service.delete(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().failure(new CustomerNotFoundException()));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> controller.delete(CUSTOMER_ID).await().indefinitely());
        }
    }

    private PageableRequestDto pageable() {
        PageableRequestDto pageable = mock(PageableRequestDto.class);
        when(pageable.getQ()).thenReturn("ignored");
        when(pageable.getPage()).thenReturn(0);
        when(pageable.getSize()).thenReturn(10);
        return pageable;
    }

    private CustomerRequestDto request() {
        return new CustomerRequestDto(
                "Jane", "Doe", "jane@example.com", "5511987654321", "529.982.247-25");
    }
}
