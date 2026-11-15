package br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EstimateProjectionTest {
    private final UUID order = UUID.randomUUID();
    private final UUID opening = UUID.randomUUID();
    private final UUID saga = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();
    private final UUID estimate = UUID.randomUUID();

    @Test
    void acceptsOnlyCorrelatedResultsWhileWaiting() {
        var waiting = EstimateProjection.opened(order, opening, saga, correlation, true);
        assertEquals(EstimateProjection.Status.AGUARDANDO, waiting.status());
        assertSame(waiting, waiting.created(UUID.randomUUID(), opening, saga, correlation, estimate));
        assertSame(waiting, waiting.created(order, UUID.randomUUID(), saga, correlation, estimate));
        assertSame(waiting, waiting.created(order, opening, UUID.randomUUID(), correlation, estimate));
        assertSame(waiting, waiting.created(order, opening, saga, UUID.randomUUID(), estimate));
        assertSame(waiting, waiting.created(order, opening, saga, correlation, null));
        var created = waiting.created(order, opening, saga, correlation, estimate);
        assertEquals(EstimateProjection.Status.CRIADO, created.status());
        assertEquals(estimate, created.estimateId());
        assertSame(created, created.failed(order, opening, saga, correlation, "FAIL", "motivo"));
    }

    @Test
    void recordsFailureAndDoesNotOverwriteIt() {
        var waiting = EstimateProjection.opened(order, opening, saga, correlation, true);
        assertSame(waiting, waiting.failed(order, opening, saga, correlation, null, "motivo"));
        assertSame(waiting, waiting.failed(order, opening, saga, correlation, " ", "motivo"));
        assertSame(waiting, waiting.failed(order, opening, saga, correlation, "FAIL", null));
        assertSame(waiting, waiting.failed(order, opening, saga, correlation, "FAIL", " "));
        var failed = waiting.failed(order, opening, saga, correlation, "FAIL", "motivo");
        assertEquals(EstimateProjection.Status.FALHOU, failed.status());
        assertEquals("FAIL", failed.failureCode());
        assertEquals("motivo", failed.failureMessage());
        assertSame(failed, failed.created(order, opening, saga, correlation, estimate));
    }

    @Test
    void openingWithoutRequestDoesNotWaitOrAcceptResults() {
        var projection = EstimateProjection.opened(order, opening, saga, correlation, false);
        assertNull(projection.status());
        assertSame(projection, projection.created(order, opening, saga, correlation, estimate));
    }
}
