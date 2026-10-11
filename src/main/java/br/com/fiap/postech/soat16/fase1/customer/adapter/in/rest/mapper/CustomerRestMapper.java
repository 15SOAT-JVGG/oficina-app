package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.mapper;

import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.request.CustomerRequestDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.response.CustomerResponseDto;
import br.com.fiap.postech.soat16.fase1.customer.application.command.CreateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.command.UpdateCustomerCommand;
import br.com.fiap.postech.soat16.fase1.customer.application.result.CustomerResult;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.application.result.PagedResult;

public final class CustomerRestMapper {

    private CustomerRestMapper() {
    }

    public static CreateCustomerCommand toCreateCommand(CustomerRequestDto request) {
        return new CreateCustomerCommand(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phoneNumber(),
                request.document());
    }

    public static UpdateCustomerCommand toUpdateCommand(UUID id, CustomerRequestDto request) {
        return new UpdateCustomerCommand(
                id,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phoneNumber());
    }

    public static PageableResponseDto<CustomerResponseDto> toResponse(
            PagedResult<CustomerResult> page) {
        return PageableResponseDto.of(
                page.content().stream().map(CustomerRestMapper::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements());
    }

    public static CustomerResponseDto toResponse(CustomerResult result) {
        if (result == null) {
            return null;
        }
        return new CustomerResponseDto(
                result.customerId(),
                result.firstName(),
                result.lastName(),
                result.email(),
                result.phoneNumber(),
                result.document(),
                result.documentType(),
                result.active(),
                result.createdAt());
    }
}
