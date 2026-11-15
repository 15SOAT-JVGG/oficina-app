package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Supplier;

import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration.EstimateResultJournal.Event;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

class EstimateResultJournalTest {
    private final EstimateResultJournal journal = new EstimateResultJournal();

    @Test
    void appliesCreatedEstimateOnlyForMatchingPendingProjection() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query inbox = mock(Mutiny.Query.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query projection = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString())).thenReturn(inbox, projection);
        when(inbox.setParameter(anyString(), any())).thenReturn(inbox);
        when(inbox.executeUpdate()).thenReturn(Uni.createFrom().item(1));
        when(projection.setParameter(anyString(), any())).thenReturn(projection);
        when(projection.executeUpdate()).thenReturn(Uni.createFrom().item(1));
        Event event = createdEvent();

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            configureTransaction(panache, session);
            assertEquals(EstimateResultJournal.Outcome.APPLIED, journal.record(event).await().indefinitely());
        }
    }

    @Test
    void reportsDuplicateWithoutChangingTheProjection() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query inbox = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString())).thenReturn(inbox);
        when(inbox.setParameter(anyString(), any())).thenReturn(inbox);
        when(inbox.executeUpdate()).thenReturn(Uni.createFrom().item(0));

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            configureTransaction(panache, session);
            assertEquals(EstimateResultJournal.Outcome.DUPLICATE,
                    journal.record(createdEvent()).await().indefinitely());
        }
    }

    @Test
    void ignoresOutOfOrderResultWhenProjectionAlreadyExists() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query inbox = mock(Mutiny.Query.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query projection = mock(Mutiny.Query.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query<Long> count = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString())).thenReturn(inbox, projection);
        when(session.createNativeQuery(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(count);
        when(inbox.setParameter(anyString(), any())).thenReturn(inbox);
        when(inbox.executeUpdate()).thenReturn(Uni.createFrom().item(1));
        when(projection.setParameter(anyString(), any())).thenReturn(projection);
        when(projection.executeUpdate()).thenReturn(Uni.createFrom().item(0));
        when(count.setParameter(anyString(), any())).thenReturn(count);
        when(count.getSingleResult()).thenReturn(Uni.createFrom().item(1L));
        Event event = failureEvent();

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            configureTransaction(panache, session);
            assertEquals(EstimateResultJournal.Outcome.IGNORED, journal.record(event).await().indefinitely());
        }
    }

    @Test
    void failsWhenProjectionHasNotArrivedYetSoInboxCanRollBack() {
        Mutiny.Session session = mock(Mutiny.Session.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query inbox = mock(Mutiny.Query.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query projection = mock(Mutiny.Query.class);
        @SuppressWarnings("unchecked")
        Mutiny.Query<Long> count = mock(Mutiny.Query.class);
        when(session.createNativeQuery(anyString())).thenReturn(inbox, projection);
        when(session.createNativeQuery(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(count);
        when(inbox.setParameter(anyString(), any())).thenReturn(inbox);
        when(inbox.executeUpdate()).thenReturn(Uni.createFrom().item(1));
        when(projection.setParameter(anyString(), any())).thenReturn(projection);
        when(projection.executeUpdate()).thenReturn(Uni.createFrom().item(0));
        when(count.setParameter(anyString(), any())).thenReturn(count);
        when(count.getSingleResult()).thenReturn(Uni.createFrom().item(0L));

        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            configureTransaction(panache, session);
            assertThrows(IllegalStateException.class,
                    () -> journal.record(failureEvent()).await().indefinitely());
        }
    }

    @Test
    void rejectsMalformedEvents() {
        assertThrows(IllegalArgumentException.class,
                () -> new Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), null, null, "", ""));
    }

    private static void configureTransaction(MockedStatic<Panache> panache, Mutiny.Session session) {
        panache.when(Panache::getSession).thenReturn(Uni.createFrom().item(session));
        panache.when(() -> Panache.withTransaction(any(Supplier.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Uni<EstimateResultJournal.Outcome>> work = invocation.getArgument(0);
            return work.get();
        });
    }

    private static Event createdEvent() {
        return new Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "{}", null, null);
    }

    private static Event failureEvent() {
        return new Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, "PART_NOT_FOUND", "Item not found");
    }
}
