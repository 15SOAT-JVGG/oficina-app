package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class SnsOutboxLocalStackIT {
    @Test
    void fifoDeliveryPreservesGroupDeduplicationAndTrace() {
        try (var stack = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8"))
                .withEnv("SERVICES", "sns,sqs").withEnv("SQS_ENDPOINT_STRATEGY", "path")
                .withExposedPorts(4566).waitingFor(Wait.forLogMessage(".*Ready\\..*\\n", 1))) {
            stack.start();
            URI endpoint = URI.create("http://" + stack.getHost() + ":" + stack.getMappedPort(4566));
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            try (var sns = SnsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                    var sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
                String topic = sns.createTopic(request -> request.name("os.fifo")
                        .attributes(Map.of("FifoTopic", "true", "ContentBasedDeduplication", "false"))).topicArn();
                String queue = sqs.createQueue(request -> request.queueName("billing.fifo")
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
                sns.subscribe(request -> request.topicArn(topic).protocol("sqs").endpoint(queueArn)
                        .attributes(Map.of("RawMessageDelivery", "true")));
                var transport = new SnsOutboxTransport();
                transport.topicArn = Optional.of(topic);
                transport.client = SnsAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                UUID order = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                String trace = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
                var message = new OutboxTransport.Message(event, order, "work-order.opened", "{}", trace);
                try {
                    transport.publish(message).await().indefinitely();
                    transport.publish(message).await().indefinitely();
                    var messages = sqs.receiveMessage(request -> request.queueUrl(queue).maxNumberOfMessages(10)
                            .waitTimeSeconds(10).messageAttributeNames("All")
                            .messageSystemAttributeNames(MessageSystemAttributeName.ALL)).messages();
                    assertEquals(1, messages.size());
                    assertEquals("{}", messages.getFirst().body());
                    assertEquals(order.toString(), messages.getFirst().attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID));
                    assertEquals(trace, messages.getFirst().messageAttributes().get("traceparent").stringValue());
                    assertEquals("work-order.opened", messages.getFirst().messageAttributes().get("type").stringValue());
                    assertTrue(sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(1)).messages().isEmpty());
                } finally {
                    transport.close();
                }
            }
        }
    }
}
