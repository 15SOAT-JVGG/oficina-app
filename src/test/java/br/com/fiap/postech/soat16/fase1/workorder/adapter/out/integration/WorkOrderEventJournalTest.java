package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

class WorkOrderEventJournalTest {
    private final WorkOrderEventJournal journal = new WorkOrderEventJournal(new ObjectMapper());

    @Test
    void writesOpeningAndNullableProjectionAtomicallyForAnEmptyRequest() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query insert = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString())).thenReturn(insert, insert);
        when(insert.setParameter(anyString(), any())).thenReturn(insert);
        when(insert.executeUpdate()).thenReturn(Uni.createFrom().item(1));
        var request = new OpenWorkOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "Revisao",
                WorkOrderPriority.LOW, null, List.of(), List.of());

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getSession).thenReturn(Uni.createFrom().item(session));
            journal.recordOpening(UUID.randomUUID(), request).await().indefinitely();
        }

        verify(insert).setParameter("status", null);
        verify(insert, org.mockito.Mockito.times(2)).executeUpdate();
    }

    @Test
    void returnsNullWhenProjectionIsMissingOrDoesNotHaveAnEstimateStatus() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query<Object[]> query = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString(), org.mockito.ArgumentMatchers.eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Uni.createFrom().item(List.of()),
                Uni.createFrom().item(List.<Object[]>of(new Object[]{null, null})));

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getSession).thenReturn(Uni.createFrom().item(session));
            assertNull(journal.findProgress(UUID.randomUUID()).await().indefinitely());
            assertNull(journal.findProgress(UUID.randomUUID()).await().indefinitely());
        }
    }

    @Test
    void mapsPersistedStatusAndFailureReasonToProgress() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query<Object[]> query = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString(), org.mockito.ArgumentMatchers.eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Uni.createFrom().item(
                List.<Object[]>of(new Object[]{"FALHOU", "Peça inexistente"})));

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getSession).thenReturn(Uni.createFrom().item(session));
            var progress = journal.findProgress(UUID.randomUUID()).await().indefinitely();
            assertEquals("FALHOU", progress.status().name());
            assertEquals("Peça inexistente", progress.failureReason());
        }
    }
}
