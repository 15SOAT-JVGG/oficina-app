package br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EstimateStatus — estados do orçamento")
class EstimateStatusTest {

    @Test
    void definesPendingApprovedAndRejectedStates() {
        assertArrayEquals(new EstimateStatus[] {
                EstimateStatus.PENDING, EstimateStatus.APPROVED, EstimateStatus.REJECTED
        }, EstimateStatus.values());
    }
}
