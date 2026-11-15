package br.com.fiap.postech.soat16.fase1.workorder.application.port.out;

import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;

import io.smallrye.mutiny.Uni;

/** Persiste evento e projeção na transação reativa já aberta pelo chamador. */
@FunctionalInterface
public interface WorkOrderEventJournalPort {
    Uni<Void> recordOpening(UUID workOrderId, OpenWorkOrderCommand request);
}
