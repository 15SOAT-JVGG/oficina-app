# Contratos provisórios de eventos da integração (issue #109)

Os schemas e exemplos v1 estão copiados como documentação e recursos de execução nos dois serviços.
A Oficina publica `work-order.opened` na outbox e consome os resultados; o Billing consome abertura,
resolve referências no seu catálogo e publica `estimate.created` ou `estimate.creation-failed`.
Testes de contrato em ambos os repositórios validam exemplos e mensagens serializadas. O fluxo do
Billing também é exercitado com PostgreSQL e SNS/SQS LocalStack. Apesar dessa cobertura, estes eventos
permanecem provisórios até validação conjunta do contrato pelo Billing e pelo OS; os exemplos não
representam eventos reais.

| Arquivo | Produtor | Consumidor |
| --- | --- | --- |
| `work-order-opened` | OS | Billing |
| `estimate-created` | Billing | OS |
| `estimate-creation-failed` | Billing | OS |

O envelope segue a spec #106; `traceparent` é atributo da mensagem, não campo JSON. SNS/SQS FIFO
agrupa por `workOrderId` e deduplica por `eventId`; a inbox persistida continua necessária. A abertura
tem `causationId` nulo. Os dois resultados usam o `eventId` da abertura como `causationId`.

Uma abertura sem solicitação inicial contém `services` e `parts` vazios e não cria orçamento inicial.
Quando há itens, identificadores são referências ao catálogo Billing e são resolvidos pelo Billing, não
pelo schema. Uma referência inválida ou serviço inativo resulta em falha sem orçamento parcial. O
schema valida a forma do evento, mas não substitui invariantes transacionais, deduplicação ou validação
de resultados contra a OS/saga/correlação/causa pendente.
