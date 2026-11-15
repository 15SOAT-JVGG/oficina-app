package br.com.fiap.postech.soat16.fase1.workorder.application.port.out;

import java.util.UUID;

import br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration.EstimateProjection;

import io.smallrye.mutiny.Uni;

@FunctionalInterface
public interface EstimateProgressPort {
    record Progress(EstimateProjection.Status status, String failureReason) { }

    Uni<Progress> findProgress(UUID workOrderId);
}
