package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.net.URI;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@ApplicationScoped
@RequiredArgsConstructor
public class SqsEstimateConsumer {
    private final EstimateResultDecoder decoder;
    private final EstimateResultJournal journal;

    @ConfigProperty(name = "app.events.sqs.queue-url")
    Optional<String> queueUrl;
    @ConfigProperty(name = "app.events.sqs.endpoint")
    Optional<String> endpoint;
    @ConfigProperty(name = "app.events.aws.region", defaultValue = "us-east-1")
    String region;
    volatile SqsAsyncClient client;

    @Scheduled(every = "${app.events.sqs.interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> poll() {
        if (queueUrl.isEmpty() || queueUrl.get().isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return receive().onFailure().invoke(error -> Log.error("Falha ao consumir resultado de orçamento", error))
                .onFailure().recoverWithNull().replaceWithVoid();
    }

    public Uni<Void> receive() {
        String queue = queueUrl.orElseThrow();
        var context = Vertx.currentContext();
        var request = ReceiveMessageRequest.builder().queueUrl(queue).maxNumberOfMessages(1)
                .waitTimeSeconds(1).messageAttributeNames("All").build();
        return Uni.createFrom().completionStage(() -> client().receiveMessage(request))
                .emitOn(task -> context.runOnContext(ignored -> task.run()))
                .flatMap(response -> {
                    if (response.messages().isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    var message = response.messages().getFirst();
                    var event = decoder.decode(message.body());
                    return journal.record(event)
                            .chain(() -> Uni.createFrom().completionStage(() -> client().deleteMessage(
                                    DeleteMessageRequest.builder().queueUrl(queue)
                                            .receiptHandle(message.receiptHandle()).build())))
                            .replaceWithVoid();
                });
    }

    private synchronized SqsAsyncClient client() {
        if (client == null) {
            var builder = SqsAsyncClient.builder().region(Region.of(region));
            endpoint.ifPresent(value -> builder.endpointOverride(URI.create(value)));
            client = builder.build();
        }
        return client;
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }
}
