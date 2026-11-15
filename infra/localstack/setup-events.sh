#!/bin/sh
set -eu

os_topic=$(awslocal sns create-topic --name os-events.fifo \
    --attributes FifoTopic=true,ContentBasedDeduplication=false --query TopicArn --output text)
billing_topic=$(awslocal sns create-topic --name billing-events.fifo \
    --attributes FifoTopic=true,ContentBasedDeduplication=false --query TopicArn --output text)
os_dlq=$(awslocal sqs create-queue --queue-name os-events-dlq.fifo --attributes FifoQueue=true --query QueueUrl --output text)
billing_dlq=$(awslocal sqs create-queue --queue-name billing-events-dlq.fifo --attributes FifoQueue=true --query QueueUrl --output text)
os_dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$os_dlq" --attribute-names QueueArn --query Attributes.QueueArn --output text)
billing_dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$billing_dlq" --attribute-names QueueArn --query Attributes.QueueArn --output text)
export os_dlq_arn billing_dlq_arn
os_attributes=$(python3 -c 'import json,os; print(json.dumps({"FifoQueue":"true","RedrivePolicy":json.dumps({"deadLetterTargetArn":os.environ["os_dlq_arn"],"maxReceiveCount":"5"})}))')
billing_attributes=$(python3 -c 'import json,os; print(json.dumps({"FifoQueue":"true","RedrivePolicy":json.dumps({"deadLetterTargetArn":os.environ["billing_dlq_arn"],"maxReceiveCount":"5"})}))')
os_queue=$(awslocal sqs create-queue --queue-name os-events.fifo --attributes "$os_attributes" --query QueueUrl --output text)
billing_queue=$(awslocal sqs create-queue --queue-name billing-events.fifo --attributes "$billing_attributes" --query QueueUrl --output text)
os_queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$os_queue" --attribute-names QueueArn --query Attributes.QueueArn --output text)
billing_queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$billing_queue" --attribute-names QueueArn --query Attributes.QueueArn --output text)

# Policies restringem a publicação ao tópico produtor; deduplicação durável continua na inbox.
export os_topic billing_topic os_queue_arn billing_queue_arn
os_policy=$(python3 -c 'import json,os; print(json.dumps({"Policy":json.dumps({"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage","Resource":os.environ["os_queue_arn"],"Condition":{"ArnEquals":{"aws:SourceArn":os.environ["billing_topic"]}}}]})}))')
billing_policy=$(python3 -c 'import json,os; print(json.dumps({"Policy":json.dumps({"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage","Resource":os.environ["billing_queue_arn"],"Condition":{"ArnEquals":{"aws:SourceArn":os.environ["os_topic"]}}}]})}))')
awslocal sqs set-queue-attributes --queue-url "$os_queue" --attributes "$os_policy"
awslocal sqs set-queue-attributes --queue-url "$billing_queue" --attributes "$billing_policy"
awslocal sns subscribe --topic-arn "$billing_topic" --protocol sqs --notification-endpoint "$os_queue_arn" \
    --attributes '{"RawMessageDelivery":"true","FilterPolicy":"{\"type\":[\"estimate.created\",\"estimate.creation-failed\"]}"}'
awslocal sns subscribe --topic-arn "$os_topic" --protocol sqs --notification-endpoint "$billing_queue_arn" \
    --attributes '{"RawMessageDelivery":"true","FilterPolicy":"{\"type\":[\"work-order.opened\"]}"}'
touch /tmp/oficina-events-ready
