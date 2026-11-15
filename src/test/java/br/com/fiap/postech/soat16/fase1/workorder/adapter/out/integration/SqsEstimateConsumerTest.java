package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsEstimateConsumerTest {
    @Test
    void deletesOnlyAfterJournalCompletes() throws Exception {
        var decoder = mock(EstimateResultDecoder.class);
        var journal = mock(EstimateResultJournal.class);
        var client = mock(SqsAsyncClient.class);
        var consumer = new SqsEstimateConsumer(decoder, journal);
        consumer.queueUrl = Optional.of("http://localhost/queue");
        consumer.client = client;
        var event = new EstimateResultJournal.Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, "ERROR", "reason");
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                ReceiveMessageResponse.builder().messages(Message.builder().body("payload").receiptHandle("receipt").build()).build()));
        when(decoder.decode("payload")).thenReturn(event);
        var commit = new CompletableFuture<EstimateResultJournal.Outcome>();
        when(journal.record(event)).thenReturn(Uni.createFrom().completionStage(commit));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        Vertx vertx = Vertx.vertx();
        try {
            var finished = new CompletableFuture<Void>();
            vertx.runOnContext(ignored -> consumer.receive().subscribe().with(finished::complete, finished::completeExceptionally));
            for (int i = 0; i < 100 && !commit.isDone(); i++) {
                if (org.mockito.Mockito.mockingDetails(journal).getInvocations().size() > 0) {
                    break;
                }
                Thread.sleep(10);
            }
            verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
            assertFalse(finished.isDone());
            commit.complete(EstimateResultJournal.Outcome.APPLIED);
            finished.get(5, TimeUnit.SECONDS);
            verify(client).deleteMessage(any(DeleteMessageRequest.class));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        consumer.close();
        verify(client).close();
    }

    @Test
    void preservesMessageWhenProcessingFails() throws Exception {
        var decoder = mock(EstimateResultDecoder.class);
        var client = mock(SqsAsyncClient.class);
        var consumer = new SqsEstimateConsumer(decoder, mock(EstimateResultJournal.class));
        consumer.queueUrl = Optional.of("http://localhost/queue");
        consumer.client = client;
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                ReceiveMessageResponse.builder().messages(Message.builder().body("bad").receiptHandle("receipt").build()).build()));
        when(decoder.decode("bad")).thenThrow(new IllegalArgumentException("invalid"));
        Vertx vertx = Vertx.vertx();
        try {
            var finished = new CompletableFuture<Void>();
            vertx.runOnContext(ignored -> consumer.receive().subscribe().with(finished::complete, finished::completeExceptionally));
            assertThrows(ExecutionException.class, () -> finished.get(5, TimeUnit.SECONDS));
            verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disabledConsumerDoesNotPoll() {
        var consumer = new SqsEstimateConsumer(mock(EstimateResultDecoder.class), mock(EstimateResultJournal.class));
        consumer.queueUrl = Optional.empty();
        consumer.poll().await().indefinitely();
    }
}
