package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.participant;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import br.com.fiap.postech.soat16.fase1.customer.application.port.out.CustomerPersistencePort;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.vehicle.application.port.out.VehiclePersistencePort;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.worker.application.port.out.WorkerPersistencePort;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;
import br.com.fiap.postech.soat16.fase1.workorder.application.port.out.WorkOrderParticipantLookupPort;

import io.smallrye.mutiny.Uni;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class WorkOrderParticipantLookupAdapter implements WorkOrderParticipantLookupPort {
    private final CustomerPersistencePort customers;
    private final VehiclePersistencePort vehicles;
    private final WorkerPersistencePort workers;

    @Override
    public Uni<Customer> findCustomerById(UUID id) {
        return customers.findByCustomerId(id);
    }

    @Override
    public Uni<Vehicle> findVehicleById(UUID id) {
        return vehicles.findByVehicleId(id);
    }

    @Override
    public Uni<Worker> findWorkerById(UUID id) {
        return workers.findByWorkerId(id);
    }
}
