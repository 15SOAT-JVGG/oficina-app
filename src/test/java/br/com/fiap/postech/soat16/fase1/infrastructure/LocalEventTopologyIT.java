package br.com.fiap.postech.soat16.fase1.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class LocalEventTopologyIT {
    @Test
    void createsFifoQueuesWithFiltersAndDeadLetterQueues() {
        try (var stack = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8"))
                .withEnv("SERVICES", "sns,sqs").withEnv("SQS_ENDPOINT_STRATEGY", "path")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/localstack/setup-events.sh")),
                        "/etc/localstack/init/ready.d/setup-events.sh")
                .withExposedPorts(4566).waitingFor(Wait.forLogMessage(".*Ready\\..*\\n", 1))) {
            stack.start();
            URI endpoint = URI.create("http://" + stack.getHost() + ":" + stack.getMappedPort(4566));
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            try (var sns = SnsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build();
                    var sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                            .credentialsProvider(credentials).build()) {
                assertEquals(2, sns.listSubscriptions().subscriptions().size());
                String queue = sqs.getQueueUrl(builder -> builder.queueName("os-events.fifo")).queueUrl();
                String redrive = sqs.getQueueAttributes(builder -> builder.queueUrl(queue)
                        .attributeNames(QueueAttributeName.REDRIVE_POLICY)).attributes().get(QueueAttributeName.REDRIVE_POLICY);
                assertTrue(redrive.contains("os-events-dlq.fifo"));
                String topic = "arn:aws:sns:us-east-1:000000000000:billing-events.fifo";
                publish(sns, topic, "unrelated.event");
                publish(sns, topic, "estimate.created");
                var received = sqs.receiveMessage(builder -> builder.queueUrl(queue).waitTimeSeconds(10)
                        .maxNumberOfMessages(10).messageAttributeNames("All")).messages();
                assertEquals(1, received.size());
                assertEquals("estimate.created", received.getFirst().messageAttributes().get("type").stringValue());
                assertEquals("{}", received.getFirst().body());
            }
        }
    }

    private void publish(SnsClient sns, String topic, String type) {
        sns.publish(builder -> builder.topicArn(topic).message("{}").messageGroupId(UUID.randomUUID().toString())
                .messageDeduplicationId(UUID.randomUUID().toString())
                .messageAttributes(Map.of("type", MessageAttributeValue.builder().dataType("String").stringValue(type).build())));
    }
}
