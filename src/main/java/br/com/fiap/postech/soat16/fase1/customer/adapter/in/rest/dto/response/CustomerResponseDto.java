package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerResponseDto(
        UUID customerId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String document,
        String documentType,
        boolean active,
        OffsetDateTime createdAt
) { }
