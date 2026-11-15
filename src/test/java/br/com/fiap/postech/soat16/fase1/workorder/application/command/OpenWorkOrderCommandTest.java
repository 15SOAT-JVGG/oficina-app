package br.com.fiap.postech.soat16.fase1.workorder.application.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;

@DisplayName("OpenWorkOrderCommand — normalização da solicitação inicial")
class OpenWorkOrderCommandTest {

    @Test
    void nullItemListsBecomeEmptyAndNoInitialRequest() {
        var command = new OpenWorkOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "Revisão",
                WorkOrderPriority.MEDIUM, null, null, null);

        assertTrue(command.services().isEmpty());
        assertTrue(command.parts().isEmpty());
        assertFalse(command.hasInitialRequest());
    }
}
