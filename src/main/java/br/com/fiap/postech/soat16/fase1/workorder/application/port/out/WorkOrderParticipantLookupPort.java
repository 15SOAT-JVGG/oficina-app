package br.com.fiap.postech.soat16.fase1.workorder.application.port.out;

import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;

import io.smallrye.mutiny.Uni;

public interface WorkOrderParticipantLookupPort {
    Uni<Customer> findCustomerById(UUID id);

    Uni<Vehicle> findVehicleById(UUID id);

    Uni<Worker> findWorkerById(UUID id);
}
