package br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.shared.adapter.out.persistence.AuditableJpaEntity;

import lombok.Getter;
import lombok.Setter;

@Entity(name = "Customer")
@Table(name = "customer", schema = "oficina_mecanica")
@Getter
@Setter
public class CustomerJpaEntity extends AuditableJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "customer_id", nullable = false)
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "document", nullable = false, unique = true)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
