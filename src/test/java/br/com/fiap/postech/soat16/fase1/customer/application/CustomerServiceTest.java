package br.com.fiap.postech.soat16.fase1.customer.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.fiap.postech.soat16.fase1.customer.application.command.CreateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.command.UpdateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.port.out.CustomerPersistencePort;
import br.com.fiap.postech.soat16.fase1.customer.application.port.out.CustomerVehicleLookupPort;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerHasVehiclesException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.CustomerNotFoundException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.DuplicateDocumentException;
import br.com.fiap.postech.soat16.fase1.customer.domain.exception.InvalidDocumentException;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;

import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService — Testes unitários")
class CustomerServiceTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String CPF_COM_MASCARA = "529.982.247-25";
    private static final String CPF_SEM_MASCARA = "52998224725";
    private static final String CNPJ_COM_MASCARA = "11.222.333/0001-81";
    private static final String CNPJ_SEM_MASCARA = "11222333000181";

    @Mock
    private CustomerPersistencePort repository;

    @Mock
    private CustomerVehicleLookupPort vehicleLookup;

    private CustomerService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new CustomerService(repository, vehicleLookup);
        customer = new Customer(
                CUSTOMER_ID,
                "John",
                "Doe",
                "john@example.com",
                "5511987654321",
                CPF_SEM_MASCARA,
                DocumentType.CPF,
                true);
    }

    @Test
    @DisplayName("deve retornar resultados paginados da aplicação")
    void shouldReturnPagedApplicationResults() {
        when(repository.findPage(0, 10)).thenReturn(Uni.createFrom().item(List.of(customer)));
        when(repository.countCustomers()).thenReturn(Uni.createFrom().item(1L));

        var result = service.findAll("ignored", 0, 10).await().indefinitely();

        assertNotNull(result);
        assertEquals(1, result.content().size());
        assertEquals(1L, result.totalElements());
        assertEquals(CUSTOMER_ID, result.content().getFirst().customerId());
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("deve retornar o cliente quando encontrado")
        void shouldReturnCustomerWhenFound() {
            when(repository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(customer));

            var result = service.findById(CUSTOMER_ID).await().indefinitely();

            assertNotNull(result);
            assertEquals(CUSTOMER_ID, result.customerId());
            assertEquals("John", result.firstName());
            assertEquals("Doe", result.lastName());
            assertEquals("john@example.com", result.email());
            assertEquals("5511987654321", result.phoneNumber());
            assertEquals(CPF_SEM_MASCARA, result.document());
            assertEquals(DocumentType.CPF.name(), result.documentType());
        }

        @Test
        @DisplayName("deve lançar CustomerNotFoundException quando o cliente não existir")
        void shouldThrowWhenCustomerIsMissing() {
            when(repository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().nullItem());

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.findById(CUSTOMER_ID).await().indefinitely());
        }
    }

    @Nested
    @DisplayName("create — validação de documento")
    class CreateWithDocument {

        @Test
        @DisplayName("deve normalizar e persistir um CPF válido")
        void shouldPersistValidCpf() {
            when(repository.existsByDocument(CPF_SEM_MASCARA))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.save(any(Customer.class)))
                    .thenReturn(Uni.createFrom().item(customer));

            assertDoesNotThrow(
                    () -> service.create(createCommand(CPF_COM_MASCARA)).await().indefinitely());

            ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
            verify(repository).save(captor.capture());
            assertEquals(CPF_SEM_MASCARA, captor.getValue().getDocument());
            assertEquals(DocumentType.CPF, captor.getValue().getDocumentType());
        }

        @Test
        @DisplayName("deve normalizar e persistir um CNPJ válido")
        void shouldPersistValidCnpj() {
            when(repository.existsByDocument(CNPJ_SEM_MASCARA))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.save(any(Customer.class)))
                    .thenReturn(Uni.createFrom().item(customer));

            assertDoesNotThrow(
                    () -> service.create(createCommand(CNPJ_COM_MASCARA)).await().indefinitely());

            ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
            verify(repository).save(captor.capture());
            assertEquals(CNPJ_SEM_MASCARA, captor.getValue().getDocument());
            assertEquals(DocumentType.CNPJ, captor.getValue().getDocumentType());
        }

        @Test
        @DisplayName("deve rejeitar documento inválido antes de acessar o repositório")
        void shouldRejectInvalidDocument() {
            assertThrows(
                    InvalidDocumentException.class,
                    () -> service.create(createCommand("123")).await().indefinitely());

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("deve rejeitar documento duplicado")
        void shouldRejectDuplicateDocument() {
            when(repository.existsByDocument(CPF_SEM_MASCARA))
                    .thenReturn(Uni.createFrom().item(true));

            assertThrows(
                    DuplicateDocumentException.class,
                    () -> service.create(createCommand(CPF_COM_MASCARA)).await().indefinitely());

            verify(repository, never()).save(any(Customer.class));
        }
    }

    @Nested
    @DisplayName("findByDocument — busca por documento")
    class FindByDocument {

        @Test
        @DisplayName("deve retornar o cliente e normalizar o documento informado")
        void shouldReturnCustomerForValidDocument() {
            when(repository.findByDocument(CPF_SEM_MASCARA))
                    .thenReturn(Uni.createFrom().item(customer));

            var result = service.findByDocument(CPF_COM_MASCARA).await().indefinitely();

            assertNotNull(result);
            assertEquals(CUSTOMER_ID, result.customerId());
            assertEquals(CPF_SEM_MASCARA, result.document());
            verify(repository).findByDocument(CPF_SEM_MASCARA);
        }

        @Test
        @DisplayName("deve rejeitar documento inválido antes de acessar o repositório")
        void shouldRejectInvalidDocument() {
            assertThrows(
                    InvalidDocumentException.class,
                    () -> service.findByDocument("abc").await().indefinitely());

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("deve informar o documento original quando o cliente não existir")
        void shouldReportRawDocumentWhenCustomerIsMissing() {
            when(repository.findByDocument(CPF_SEM_MASCARA))
                    .thenReturn(Uni.createFrom().nullItem());

            CustomerNotFoundException exception = assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.findByDocument(CPF_COM_MASCARA).await().indefinitely());

            assertTrue(exception.getMessage().contains(CPF_COM_MASCARA));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("deve atualizar os dados sem alterar o documento")
        void shouldUpdateCustomerWithoutChangingDocument() {
            UpdateCustomerCommand command = updateCommand();
            when(repository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(customer));
            when(repository.save(customer)).thenReturn(Uni.createFrom().item(customer));

            var result = service.update(command).await().indefinitely();

            assertEquals("Jane", result.firstName());
            assertEquals("Smith", result.lastName());
            assertEquals("jane@example.com", result.email());
            assertEquals("11999999999", result.phoneNumber());
            assertEquals(CPF_SEM_MASCARA, result.document());
        }

        @Test
        @DisplayName("deve lançar CustomerNotFoundException quando o cliente não existir")
        void shouldThrowWhenCustomerIsMissing() {
            when(repository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().nullItem());

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.update(updateCommand()).await().indefinitely());

            verify(repository, never()).save(any(Customer.class));
        }
    }

    @Nested
    @DisplayName("activate / deactivate")
    class Status {

        @Test
        @DisplayName("deve desativar o cliente existente e persistir o novo status")
        void shouldDeactivateCustomer() {
            when(repository.findByCustomerId(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(repository.save(customer)).thenReturn(Uni.createFrom().item(customer));

            var result = service.deactivate(CUSTOMER_ID).await().indefinitely();

            assertFalse(result.active());
            assertFalse(customer.isActive());
        }

        @Test
        @DisplayName("deve reativar o cliente inativo e persistir o novo status")
        void shouldActivateCustomer() {
            customer.deactivate();
            when(repository.findByCustomerId(CUSTOMER_ID)).thenReturn(Uni.createFrom().item(customer));
            when(repository.save(customer)).thenReturn(Uni.createFrom().item(customer));

            var result = service.activate(CUSTOMER_ID).await().indefinitely();

            assertTrue(result.active());
            assertTrue(customer.isActive());
        }

        @Test
        @DisplayName("deve lançar CustomerNotFoundException ao desativar cliente inexistente")
        void shouldThrowWhenDeactivatingMissingCustomer() {
            when(repository.findByCustomerId(CUSTOMER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.deactivate(CUSTOMER_ID).await().indefinitely());

            verify(repository, never()).save(any(Customer.class));
        }

        @Test
        @DisplayName("deve lançar CustomerNotFoundException ao ativar cliente inexistente")
        void shouldThrowWhenActivatingMissingCustomer() {
            when(repository.findByCustomerId(CUSTOMER_ID)).thenReturn(Uni.createFrom().nullItem());

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.activate(CUSTOMER_ID).await().indefinitely());

            verify(repository, never()).save(any(Customer.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("deve excluir o cliente sem veículos associados")
        void shouldDeleteCustomerWithoutVehicles() {
            when(vehicleLookup.existsByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.deleteByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(1L));

            assertDoesNotThrow(() -> service.delete(CUSTOMER_ID).await().indefinitely());

            verify(repository).deleteByCustomerId(CUSTOMER_ID);
        }

        @Test
        @DisplayName("deve lançar CustomerNotFoundException quando nenhuma linha for excluída")
        void shouldThrowWhenNoCustomerIsDeleted() {
            when(vehicleLookup.existsByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.deleteByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(0L));

            assertThrows(
                    CustomerNotFoundException.class,
                    () -> service.delete(CUSTOMER_ID).await().indefinitely());
        }

        @Test
        @DisplayName("deve impedir a exclusão de cliente com veículos associados")
        void shouldPreventDeletionWhenCustomerHasVehicles() {
            when(vehicleLookup.existsByCustomerId(CUSTOMER_ID))
                    .thenReturn(Uni.createFrom().item(true));

            assertThrows(
                    CustomerHasVehiclesException.class,
                    () -> service.delete(CUSTOMER_ID).await().indefinitely());

            verify(repository, never()).deleteByCustomerId(CUSTOMER_ID);
        }
    }

    private CreateCustomerCommand createCommand(String document) {
        return new CreateCustomerCommand(
                "John", "Doe", "john@example.com", "5511987654321", document);
    }

    private UpdateCustomerCommand updateCommand() {
        return new UpdateCustomerCommand(
                CUSTOMER_ID, "Jane", "Smith", "jane@example.com", "11999999999");
    }
}
