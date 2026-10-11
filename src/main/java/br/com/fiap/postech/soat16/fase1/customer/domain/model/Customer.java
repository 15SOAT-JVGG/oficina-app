package br.com.fiap.postech.soat16.fase1.customer.domain.model;

import java.util.Objects;
import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.shared.domain.model.audit.AuditableEntity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Customer extends AuditableEntity {

    private UUID id;

    private String firstName;

    private String lastName;

    private String email;

    private String phoneNumber;

    private String document;

    private DocumentType documentType;

    /**
     * Só o cliente ativo se autentica pelo CPF; a ficha e o histórico de um inativo continuam
     * disponíveis à oficina.
     */
    private boolean active = true;

    public static Customer create(String firstName, String lastName, String email,
            String phoneNumber, Document document) {
        var customer = new Customer();
        customer.firstName = firstName;
        customer.lastName = lastName;
        customer.email = email;
        customer.phoneNumber = phoneNumber;
        customer.document = document.getValue();
        customer.documentType = document.getType();
        customer.active = true;
        return customer;
    }

    public void update(String firstName, String lastName, String email, String phoneNumber) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phoneNumber = phoneNumber;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /**
     * Identidade só existe após a persistência: duas instâncias ainda sem id nunca são iguais.
     */
    @Override
    public final boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        Customer customer = (Customer) object;
        return id != null && Objects.equals(id, customer.id);
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }
}
