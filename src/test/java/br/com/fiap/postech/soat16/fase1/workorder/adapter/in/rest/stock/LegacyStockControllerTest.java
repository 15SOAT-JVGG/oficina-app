package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import jakarta.ws.rs.WebApplicationException;

import org.junit.jupiter.api.Test;

class LegacyStockControllerTest {
    @Test
    void stockOperationsAreUnavailable() {
        var controller = new LegacyStockController();
        var low = assertThrows(WebApplicationException.class, () -> controller.findLowStock().await().indefinitely());
        var adjust = assertThrows(WebApplicationException.class,
                () -> controller.adjustStock(UUID.randomUUID(), 5).await().indefinitely());
        assertEquals(503, low.getResponse().getStatus());
        assertEquals(503, adjust.getResponse().getStatus());
    }
}
