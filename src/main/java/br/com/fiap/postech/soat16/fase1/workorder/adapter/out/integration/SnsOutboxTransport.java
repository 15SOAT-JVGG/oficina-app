package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@ApplicationScoped
public class SnsOutboxTransport implements OutboxTransport {
    @ConfigProperty(name = "app.events.sns.topic-arn")
    Optional<String> topicArn;

    @ConfigProperty(name = "app.events.sns.endpoint")
    Optional<String> endpoint;

    @ConfigProperty(name = "app.events.aws.region", defaultValue = "us-east-1")
    String region;

    volatile SnsAsyncClient client;

    @Override
    public boolean isConfigured() {
        return topicArn.filter(value -> !value.isBlank()).isPresent();
    }

    @Override
    public Uni<Void> publish(Message message) {
        var attributes = new HashMap<String, MessageAttributeValue>();
        attributes.put("type", attribute(message.type()));
        if (message.traceparent() != null && !message.traceparent().isBlank()) {
            attributes.put("traceparent", attribute(message.traceparent()));
        }
        var request = PublishRequest.builder().topicArn(topicArn.orElseThrow())
                .message(message.payload()).messageGroupId(message.workOrderId().toString())
                .messageDeduplicationId(message.eventId().toString()).messageAttributes(attributes).build();
        return Uni.createFrom().completionStage(() -> client().publish(request)).replaceWithVoid();
    }

    private static MessageAttributeValue attribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    private synchronized SnsAsyncClient client() {
        if (client == null) {
            var builder = SnsAsyncClient.builder().region(Region.of(region));
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
