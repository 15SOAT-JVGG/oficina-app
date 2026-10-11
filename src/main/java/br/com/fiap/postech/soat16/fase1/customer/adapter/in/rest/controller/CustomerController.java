package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.controller;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;

import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.request.CustomerRequestDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.response.CustomerResponseDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.mapper.CustomerRestMapper;
import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.openapi.CustomerControllerDocs;
import br.com.fiap.postech.soat16.fase1.customer.application.CustomerService;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableRequestDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;

import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
@Path("/v1/customer")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MECHANIC"})
public class CustomerController implements CustomerControllerDocs {

    private final CustomerService service;

    @GET
    @Override
    public Uni<PageableResponseDto<CustomerResponseDto>> findAll(
            @BeanParam @Valid PageableRequestDto pageable) {
        return service.findAll(pageable.getQ(), pageable.getPage(), pageable.getSize())
                .map(CustomerRestMapper::toResponse);
    }

    @GET
    @Path("/{id}")
    @Override
    public Uni<CustomerResponseDto> findById(@PathParam("id") UUID id) {
        return service.findById(id).map(CustomerRestMapper::toResponse);
    }

    @GET
    @Path("/by-document/{document}")
    @Override
    public Uni<CustomerResponseDto> findByDocument(@PathParam("document") String document) {
        return service.findByDocument(document).map(CustomerRestMapper::toResponse);
    }

    @POST
    @RolesAllowed("ADMIN")
    @Override
    public Uni<Response> create(@RequestBody @Valid CustomerRequestDto dto) {
        return service.create(CustomerRestMapper.toCreateCommand(dto))
                .replaceWith(Response.status(Response.Status.CREATED).build());
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    @Override
    public Uni<Response> update(@PathParam("id") UUID id,
            @RequestBody @Valid CustomerRequestDto dto) {
        return service.update(CustomerRestMapper.toUpdateCommand(id, dto))
                .map(CustomerRestMapper::toResponse)
                .map(updated -> Response.ok(updated).build());
    }

    @PATCH
    @Path("/{id}/activate")
    @RolesAllowed("ADMIN")
    @Override
    public Uni<CustomerResponseDto> activate(@PathParam("id") UUID id) {
        return service.activate(id).map(CustomerRestMapper::toResponse);
    }

    @PATCH
    @Path("/{id}/deactivate")
    @RolesAllowed("ADMIN")
    @Override
    public Uni<CustomerResponseDto> deactivate(@PathParam("id") UUID id) {
        return service.deactivate(id).map(CustomerRestMapper::toResponse);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    @Override
    public Uni<Response> delete(@PathParam("id") UUID id) {
        return service.delete(id)
                .replaceWith(Response.noContent().build());
    }
}
