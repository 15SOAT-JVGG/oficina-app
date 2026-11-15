package br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration;

import java.util.UUID;

/** Estado local da solicitação inicial de orçamento; resultados finais são imutáveis. */
public record EstimateProjection(
        UUID workOrderId,
        UUID openingEventId,
        UUID sagaId,
        UUID correlationId,
        Status status,
        UUID estimateId,
        String failureCode,
        String failureMessage
) {
    public enum Status {
        AGUARDANDO, CRIADO, FALHOU
    }

    public static EstimateProjection opened(UUID workOrderId, UUID openingEventId, UUID sagaId,
            UUID correlationId, boolean requested) {
        return new EstimateProjection(workOrderId, openingEventId, sagaId, correlationId,
                requested ? Status.AGUARDANDO : null, null, null, null);
    }

    public EstimateProjection created(UUID eventWorkOrderId, UUID causationId, UUID eventSagaId,
            UUID eventCorrelationId, UUID createdEstimateId) {
        if (!accepts(eventWorkOrderId, causationId, eventSagaId, eventCorrelationId) || createdEstimateId == null) {
            return this;
        }
        return new EstimateProjection(workOrderId, openingEventId, sagaId, correlationId,
                Status.CRIADO, createdEstimateId, null, null);
    }

    public EstimateProjection failed(UUID eventWorkOrderId, UUID causationId, UUID eventSagaId,
            UUID eventCorrelationId, String code, String message) {
        if (!accepts(eventWorkOrderId, causationId, eventSagaId, eventCorrelationId)
                || code == null || code.isBlank() || message == null || message.isBlank()) {
            return this;
        }
        return new EstimateProjection(workOrderId, openingEventId, sagaId, correlationId,
                Status.FALHOU, null, code, message);
    }

    private boolean accepts(UUID eventWorkOrderId, UUID causationId, UUID eventSagaId, UUID eventCorrelationId) {
        return status == Status.AGUARDANDO && workOrderId.equals(eventWorkOrderId)
                && openingEventId.equals(causationId) && sagaId.equals(eventSagaId)
                && correlationId.equals(eventCorrelationId);
    }
}
