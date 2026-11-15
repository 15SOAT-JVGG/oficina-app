# Fluxo da Ordem de Serviço (Work Order)

Este documento mantém o histórico do ciclo de vida original da **ordem de serviço**. A transição da
#109 mudou o contrato: veja a seção [Integração de abertura OS → Billing](README.md#integração-de-abertura-os--billing-transição-da-issue-109)
para o comportamento vigente. As operações de orçamento, estoque e execução descritas adiante estão
desativadas e respondem `503 LEGACY_FLOW_UNAVAILABLE` até as integrações com Billing e Execution
serem concluídas. Para detalhes de autenticação e execução local, veja o [README.md](README.md).

---

## Visão geral das entidades

| Entidade | O que representa |
|---|---|
| `WorkOrder` | A ordem de serviço em si: cliente, veículo, mecânico responsável, descrição, prioridade, status e valores. |
| `Estimate` | Um orçamento de peças vinculado à ordem de serviço. Pode ser aprovado. |
| `EstimateItem` | Um item do orçamento: uma peça do catálogo (`Part`), quantidade e preço unitário. |
| `WorkOrderService` | Uma linha de mão de obra executada na ordem (ex.: "Troca de óleo"), com descrição, preço e item do catálogo de serviços de origem. |
| `WorkOrderHistory` | Registro interno de toda mudança de status da ordem (não exposto via API). |
| `EstimateDecisionToken` | O direito a uma única decisão do cliente sobre um orçamento. Um token por decisão (aprovar/recusar), assinado, de uso único e válido por sete dias. |
| `WorkOrderTrackingToken` | O direito de acompanhar uma ordem de serviço. Assinado, sem registro em banco, reemitido a cada aviso e válido por trinta dias. |

---

## Máquina de estados (`status`)

```
RECEIVED ──► DIAGNOSIS ──► WAITING_APPROVAL ──► IN_PROGRESS ──► COMPLETED ──► DELIVERED
                                  │
                                  └── recusa ──► COMPLETED + cancelledAt
```

- **RECEIVED** é o status inicial — definido automaticamente na criação, junto com `openedAt`.
- **WAITING_APPROVAL** é o ponto em que a oficina se compromete com o cliente: a mesma transação
  reserva no estoque todas as peças do orçamento pendente e envia ao cliente um e-mail com os links
  de aprovação e recusa. Se qualquer peça não tiver saldo, nada é reservado, nenhum e-mail sai e a
  OS permanece em `DIAGNOSIS` (`INSUFFICIENT_PART_STOCK`, HTTP 422).
- **COMPLETED** é alcançado pelo endpoint dedicado `PATCH /{id}/close` ou pela recusa do orçamento.
- **DELIVERED** só pode ser definido a partir de `COMPLETED`, via `PATCH /{id}/status`.
- Não existe cancelamento manual nem status `CANCELLED`: a recusa conclui a OS e preenche
  `cancelledAt`.
- **DELIVERED** e uma OS concluída por recusa são terminais: qualquer tentativa de alterá-las
  retorna erro (`WorkOrderLockedException`, HTTP 422).
- Não é permitido **pular etapas** via `PATCH /status` (ex.: ir de `RECEIVED` direto para
  `IN_PROGRESS`).
- Toda mudança de status (pelo `PATCH /status`, pelo `/close`, ou implicitamente ao aprovar um
  orçamento ou registrar uma recusa) gera um registro em `WorkOrderHistory` com status anterior,
  novo status e data/hora.

### Prioridade (`priority`)

`LOW`, `MEDIUM` (padrão quando não informada), `HIGH`, `URGENT`. A prioridade é um atributo de
triagem da ordem; ela não influencia a ordenação da listagem operacional.

### Fila operacional (`GET /v1/work-orders`)

A listagem administrativa é a fila de trabalho da oficina, e não um espelho de tudo que já passou
por ela:

- só entram as OS ainda em atendimento — `COMPLETED` e `DELIVERED` ficam de fora, inclusive as
  concluídas por recusa do orçamento;
- os grupos aparecem na ordem `IN_PROGRESS`, `WAITING_APPROVAL`, `DIAGNOSIS` e `RECEIVED`;
- dentro do mesmo status, a OS aberta há mais tempo vem primeiro;
- a contagem e a paginação (`page`, `size`) consideram exatamente esse conjunto filtrado;
- a ordenação é a da fila, então os parâmetros `q` e `sort` do contrato de paginação compartilhado
  são aceitos e ignorados nesta rota.

---

## Regras de orçamento (Estimate)

- O orçamento inicial nasce junto da OS quando a abertura traz `services` ou `parts`, com status
  `PENDING` e sem `sentAt` — ele só é enviado ao cliente mais adiante no ciclo.
- `EstimateItem.totalPrice = quantity * unitPrice`. Se `unitPrice` não for informado na requisição,
  o valor unitário da peça no catálogo (`Part.unitPrice`) é usado. Na abertura o preço é sempre o do
  catálogo, copiado como snapshot.
- `Estimate.totalAmount` é a soma do `totalPrice` de todos os itens.
- Ao **aprovar** um orçamento:
  - `WorkOrder.estimatedValue` recebe o `totalAmount` do orçamento aprovado.
  - Se a ordem estiver em `WAITING_APPROVAL`, ela avança automaticamente para `IN_PROGRESS` (gerando
    histórico). Se já estiver em outro status, apenas o `estimatedValue` é atualizado.
  - A reserva de estoque feita na entrada em `WAITING_APPROVAL` é mantida: a aprovação apenas
    confirma o compromisso com a execução.
  - Um orçamento já aprovado ou rejeitado não pode ser aprovado novamente (`EstimateAlreadyDecidedException`, HTTP 409).
- Ao **recusar** um orçamento pendente em `WAITING_APPROVAL`, as peças reservadas voltam ao estoque,
  a OS avança para `COMPLETED`, recebe `closedAt` e `cancelledAt` e fica bloqueada para novas
  alterações.

### Acompanhamento do cliente por e-mail

A abertura da OS e cada mudança de status posterior enviam ao cliente um e-mail contando em que
estágio o atendimento está, com um link de acompanhamento
(`GET /v1/public/work-orders/tracking/{token}`).

- O link carrega um `WorkOrderTrackingToken` assinado com o par RS256 da API — um token **distinto
  do de decisão**: acompanhar não altera nada, então o link vale por **trinta dias** e por quantas
  consultas o cliente quiser.
- Como não há nada a gastar, o token não é registrado no banco: a data de emissão viaja assinada no
  link e é dela que sai o prazo. Cada aviso reemite o link, então o cliente sempre tem trinta dias
  pela frente a partir da última novidade.
- A resposta conta apenas o andamento — `workOrderId`, `status`, `openedAt`, `closedAt` e
  `cancelledAt`. Valores, peças, descrição e mecânico ficam fora, porque um e-mail é reencaminhado.
- Não existe consulta por id: sem o link, ou com um link forjado (`TRACKING_TOKEN_INVALID`, 400) ou
  vencido (`TRACKING_TOKEN_EXPIRED`, 410), nada da OS é revelado.

### Decisão do cliente por e-mail

Ao entrar em `WAITING_APPROVAL`, a oficina envia por SMTP um e-mail ao cliente com dois links, um
para aprovar e outro para recusar. Cada link carrega um `EstimateDecisionToken` assinado com o par
RS256 da API e gravado no banco.

- A assinatura prova que o link saiu da oficina; o registro no banco é o que garante o **uso único**,
  porque uma assinatura continua válida a cada vez que é apresentada.
- O prazo é de **sete dias** a partir da emissão.
- Decidido o orçamento por um dos links, o outro deixa de valer (`ESTIMATE_ALREADY_DECIDED`, 409).
- Toda tentativa recusada — link forjado, expirado ou já usado — não altera OS nem estoque.

O endpoint é um `POST`, e não um `GET`, porque o token vale uma única vez: um cliente de e-mail que
pré-carrega os links da mensagem consumiria a decisão sem que o cliente a tomasse.

O envio depende da configuração SMTP (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
`MAIL_FROM`) e do endereço público que forma os links (`APP_PUBLIC_BASE_URL`). Sem `MAIL_HOST` o
mailer opera em modo simulado e apenas registra a mensagem em log.
- Uma ordem **não pode iniciar a execução** (`IN_PROGRESS`) **nem ser fechada** (`/close`) sem um
  orçamento aprovado (`EstimateNotApprovedException`, HTTP 422).

---

## Endpoints

Acesso, na notação do [modelo de acesso](docs/FASE-1.md#mapa-de-endpoints): 🔓 público (só o link
assinado) · 🔧 `ADMIN` e `MECHANIC` · 🛡️ só `ADMIN`. Sem token, as rotas administrativas respondem
`401`; a métrica de tempo médio, a única restrita por papel aqui, responde `403` ao `MECHANIC`.

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| `POST` | `/v1/work-orders` | 🔧 | Abre uma nova ordem de serviço e, junto dela, o orçamento pendente da solicitação inicial. |
| `GET` | `/v1/work-orders/{id}` | 🔧 | Busca uma ordem por id. |
| `GET` | `/v1/work-orders` | 🔧 | Lista a fila operacional: apenas OS em atendimento, paginadas e ordenadas por estágio de trabalho. |
| `GET` | `/v1/work-orders/metrics/average-execution-time` | 🛡️ | Tempo médio de execução (abertura → conclusão) das OS já encerradas, em minutos, e o tamanho da amostra. |
| `PATCH` | `/v1/work-orders/{id}/status` | 🔧 | Avança o status da ordem (exceto para `COMPLETED`). |
| `POST` | `/v1/work-orders/{id}/estimate` | 🔧 | Cria um orçamento com itens do catálogo de peças. |
| `PATCH` | `/v1/work-orders/{id}/estimate/{estimateId}/approve` | 🔧 | Aprova um orçamento pendente. |
| `PATCH` | `/v1/work-orders/{id}/estimate/{estimateId}/reject` | 🔧 | Recusa o orçamento, devolve as peças ao estoque e conclui a OS com `cancelledAt`. |
| `GET` | `/v1/public/work-orders/tracking/{token}` | 🔓 | Canal do cliente: acompanha a OS pelo link recebido por e-mail. |
| `POST` | `/v1/public/work-orders/estimate-decisions/{token}` | 🔓 | Canal do cliente: registra a decisão pelo link recebido por e-mail. |
| `POST` | `/v1/work-orders/{id}/services` | 🔧 | Registra uma linha de mão de obra executada. |
| `PATCH` | `/v1/work-orders/{id}/close` | 🔧 | Finaliza a ordem (`IN_PROGRESS` → `COMPLETED`). |

---

## Passo a passo (exemplo completo)

> Os exemplos usam `curl`. Troque `$TOKEN` pelo token JWT (veja [docs/FASE-1.md](docs/FASE-1.md#autenticação-jwt)) se o ambiente exigir autenticação, e os UUIDs pelos valores reais retornados em cada chamada.

### 1. Abrir a ordem de serviço

```shell
curl -s -X POST http://localhost:8080/v1/work-orders \
  -H "Content-Type: application/json" \
  -d '{
        "customerId": "<customer-uuid>",
        "vehicleId": "<vehicle-uuid>",
        "description": "Revisão dos 10.000km",
        "priority": "HIGH",
        "assignedWorkerId": "<worker-uuid>",
        "services": [{"serviceItemId": "<service-item-uuid>"}],
        "parts": [{"partId": "<part-uuid>", "quantity": 2}]
      }'
```

A ordem é criada com `status = RECEIVED`, `openedAt` preenchido automaticamente. `priority` é opcional —
se omitido, assume `MEDIUM`. `assignedWorkerId` — identifica o mecânico responsável pela OS.

`services` e `parts` formam a **solicitação inicial** e são opcionais. Quando qualquer um deles vem
preenchido, a mesma transação cria a OS e o orçamento pendente correspondente: os serviços viram
linhas de mão de obra com o `basePrice` do catálogo e as peças viram itens do orçamento com o
`unitPrice` do catálogo. Como os preços são copiados na abertura, uma atualização posterior do
catálogo não altera o orçamento já emitido. Uma referência inválida (cliente, veículo, mecânico,
item de serviço ou peça) faz a requisição inteira falhar sem deixar dados parciais.

A resposta `201` traz a ordem aberta e o orçamento inicial, além do cabeçalho `Location` apontando
para a nova OS:

```json
{
  "workOrder": {"workOrderId": "...", "status": "RECEIVED", "estimatedValue": 270.00},
  "estimate": {"estimateId": "...", "status": "PENDING", "partsAmount": 150.00,
               "laborAmount": 120.00, "totalAmount": 270.00, "items": []}
}
```

`estimate` vem `null` quando a abertura não traz solicitação inicial.

A abertura também dispara o primeiro e-mail de acompanhamento ao cliente. Ele acompanha a OS pelo
link recebido, sem autenticação:

```shell
curl -s http://localhost:8080/v1/public/work-orders/tracking/<tracking-token>
```

```json
{"workOrderId": "...", "status": "RECEIVED", "openedAt": "2026-01-10T09:00:00"}
```

### 2. Avançar para diagnóstico

```shell
curl -s -X PATCH http://localhost:8080/v1/work-orders/<work-order-id>/status \
  -H "Content-Type: application/json" \
  -d '{"status": "DIAGNOSIS"}'
```

### 3. Criar o orçamento

```shell
curl -s -X POST http://localhost:8080/v1/work-orders/<work-order-id>/estimate \
  -H "Content-Type: application/json" \
  -d '{
        "items": [
          {"partId": "<part-uuid-1>", "quantity": 1},
          {"partId": "<part-uuid-2>", "quantity": 4, "unitPrice": 25.50}
        ]
      }'
```

A resposta traz o `estimateId`, o `totalAmount` calculado e cada item com seu `totalPrice`.
A criação do orçamento move a ordem automaticamente de `DIAGNOSIS` para `WAITING_APPROVAL` — e é
essa entrada que reserva as peças no estoque e dispara o e-mail de decisão ao cliente. Se faltar
saldo de qualquer peça, a requisição inteira falha com `422` e a ordem continua em `DIAGNOSIS`.

### 4. Aprovar o orçamento

O caminho normal é o cliente clicar no link de aprovação que recebeu por e-mail:

```shell
curl -s -X POST http://localhost:8080/v1/public/work-orders/estimate-decisions/<token>
```

A oficina também pode registrar a decisão pelo canal administrativo, quando o cliente responde por
outro meio:

```shell
curl -s -X PATCH http://localhost:8080/v1/work-orders/<work-order-id>/estimate/<estimate-id>/approve
```

Em qualquer um dos caminhos, a ordem (que estava em `WAITING_APPROVAL`) passa automaticamente para
`IN_PROGRESS`, `estimatedValue` recebe o `totalAmount` do orçamento e a reserva de estoque é
mantida. O orçamento só aceita uma decisão: a segunda tentativa responde `409`.

### 5. Registrar a mão de obra executada

```shell
curl -s -X POST http://localhost:8080/v1/work-orders/<work-order-id>/services \
  -H "Content-Type: application/json" \
  -d '{"description": "Troca de óleo e filtros", "price": 120.00, "serviceItemId": "<service-item-uuid>"}'
```

Pode ser chamado várias vezes — cada chamada cria uma linha de `WorkOrderService` independente.
`serviceItemId` — vincula a linha ao item do catálogo de serviços que a originou.

### 6. Fechar a ordem

```shell
curl -s -X PATCH http://localhost:8080/v1/work-orders/<work-order-id>/close \
  -H "Content-Type: application/json" \
  -d '{"finalValue": 410.50}'
```

Exige status `IN_PROGRESS` e orçamento aprovado. `finalValue` é opcional — se omitido, usa o
`estimatedValue` da ordem. Pode ser usado para registrar um valor final diferente do orçamento
aprovado (ex.: serviço extra identificado durante a execução ou desconto concedido); quando
informado, deve ser maior que zero. Define `closedAt` e move o status para `COMPLETED`.

### 7. Entregar o veículo

```shell
curl -s -X PATCH http://localhost:8080/v1/work-orders/<work-order-id>/status \
  -H "Content-Type: application/json" -d '{"status": "DELIVERED"}'
```

A partir daqui a ordem está **bloqueada**: nenhuma alteração de status, orçamento ou serviço é
mais aceita.

### Recusar o orçamento

O cliente recusa pelo link de recusa que recebeu por e-mail:

```shell
curl -s -X POST http://localhost:8080/v1/public/work-orders/estimate-decisions/<token>
```

As peças reservadas voltam ao estoque, a OS passa para `COMPLETED`, recebe `closedAt` e
`cancelledAt` e não pode ser entregue nem alterada. Não existe endpoint de cancelamento manual.

---

## Erros mais comuns

| HTTP | Código | Quando acontece |
|---|---|---|
| 400 | `DECISION_TOKEN_INVALID` | Link de decisão adulterado, malformado ou não emitido para decidir orçamento. |
| 400 | `TRACKING_TOKEN_INVALID` | Link de acompanhamento adulterado, malformado ou não emitido para acompanhar uma OS. |
| 401 | — | Rota administrativa chamada sem `Authorization: Bearer`. |
| 403 | — | `MECHANIC` pedindo a métrica de tempo médio, restrita ao `ADMIN`. |
| 404 | `WORK_ORDER_NOT_FOUND` | Id de ordem inexistente. |
| 404 | `WORKER_NOT_FOUND` | `assignedWorkerId` informado na abertura não existe. |
| 404 | `ESTIMATE_PART_NOT_FOUND` | `partId` informado no orçamento não existe no catálogo. |
| 404 | `ESTIMATE_NOT_FOUND` | `estimateId` não existe ou não pertence à ordem informada. |
| 409 | `ESTIMATE_ALREADY_DECIDED` | Tentativa de aprovar um orçamento já aprovado/rejeitado. |
| 410 | `DECISION_TOKEN_EXPIRED` | Link de decisão apresentado depois dos sete dias. |
| 410 | `DECISION_TOKEN_ALREADY_USED` | Link de decisão apresentado uma segunda vez. |
| 410 | `TRACKING_TOKEN_EXPIRED` | Link de acompanhamento apresentado depois dos trinta dias. |
| 422 | `INSUFFICIENT_PART_STOCK` | Saldo insuficiente para reservar uma peça ao levar a OS a `WAITING_APPROVAL`. |
| 422 | `WORK_ORDER_LOCKED` | Ordem já `DELIVERED` ou concluída por recusa do orçamento. |
| 422 | `INVALID_STATUS_TRANSITION` | Transição de status inválida (pular etapa, ir direto para `COMPLETED`, etc.). |
| 422 | `ESTIMATE_NOT_APPROVED` | Tentativa de iniciar (`IN_PROGRESS`) ou fechar (`/close`) sem orçamento aprovado. |

---

## Cobertura de testes

O fluxo é validado em duas camadas, ambas no pacote `br.com.fiap.postech.soat16.fase1`:

- **Unitário** — `workorder.application.WorkOrderServiceTest`: regras de negócio isoladas, portas mockadas.
  Cobre também transições artificiais (ex.: travas combinadas) difíceis de reproduzir via API.
- **Integração** — `workorder.adapter.in.rest.controller.WorkOrderControllerIT`: mesmo fluxo no endpoint HTTP real
  contra um PostgreSQL via Testcontainers (`./mvnw test -Pitest`, requer Docker) — valida
  serialização JSON, mapeamento JPA/Hibernate Reactive e Bean Validation de ponta a ponta.

Dois `*IT` cobrem o que cerca o fluxo: `auth.adapter.in.rest.SecurityIT` guarda o `401` sem token e o
`403` do `MECHANIC` na métrica restrita ao `ADMIN`, e
`workorder.adapter.in.rest.openapi.WorkOrderOpenApiContractIT` confere o documento servido em
`/q/openapi` — as respostas declaradas em cada operação, incluindo `401` e `403`, e os efeitos de
estoque descritos no orçamento.

A tabela liga cada regra já descrita neste documento ao teste que a exercita (nomes abreviados
como `Classe.método`; todas vivem dentro de uma classe `@Nested` com o mesmo nome do endpoint):

| Regra de negócio | Integração (`WorkOrderControllerIT`) | Unitário (`WorkOrderServiceTest`) |
|---|---|---|
| Ordem nasce em `RECEIVED`, `openedAt` automático | `FullLifecycle.shouldCompleteFullLifecycle`, `Create.shouldCreateWorkOrder` | `Create.shouldPersistWorkOrder...` |
| Abertura devolve a identificação da OS no corpo e no `Location` | `Create.shouldReturnCreatedWorkOrderIdentification` | `WorkOrderControllerTest.Create.shouldReturn201WhenCreateSucceeds` |
| Solicitação inicial abre OS e orçamento pendente na mesma transação | `Create.shouldOpenWorkOrderWithInitialPendingEstimate` | `Create.shouldCreatePendingEstimateFromInitialRequest` |
| Preços do orçamento são snapshot do catálogo | `Create.shouldKeepEstimatePricesAfterCatalogUpdate` | `Create.shouldSnapshotServicePrice` |
| Item de serviço ou peça inválido na abertura → 404 sem dados parciais | `Create.shouldReturn404WhenServiceItemNotFound` / `...RequestedPartNotFound` | `Create.shouldThrowWhenRequestedServiceItemMissing` / `...RequestedPartMissing` |
| Cliente/veículo inexistente ao criar → 404 | `Create.shouldReturn404WhenCustomerNotFound` / `...VehicleNotFound` | `Create.shouldThrow...NotFoundException` |
| Mecânico responsável é registrado na abertura; se não existir → 404 sem dados parciais | `Create.shouldAssignTheRequestedWorker`, `...shouldReturn404WhenAssignedWorkerNotFound` | — (exercitado ponta a ponta) |
| Não é permitido pular etapas (ex.: `RECEIVED` → `IN_PROGRESS` direto) | `UpdateStatus.shouldRejectSkippingCanonicalStages` | `UpdateStatus.shouldRejectSkippingStages` |
| `COMPLETED` só via `/close`, nunca pelo `/status` | `UpdateStatus.shouldRejectCompletedViaGenericEndpoint` | `UpdateStatus.shouldRejectJumpingDirectlyToCompleted` |
| A recusa conclui a OS e preenche `cancelledAt` | `EstimateDecisionAndStock.shouldRejectEstimateAndCompleteWorkOrder` | `RejectEstimate.shouldRejectAndCompleteWorkOrder` |
| `DELIVERED` e conclusão por recusa bloqueiam alterações (`WORK_ORDER_LOCKED`) | `FullLifecycle.shouldLockWorkOrderAfterDelivered`, `EstimateDecisionAndStock.shouldLockWorkOrderAfterEstimateRejection` | `WorkOrderTest.rejectsDeliveryAfterEstimateRejection` |
| Orçamento aprovado grava `estimatedValue` na ordem | `ApproveEstimate.shouldApproveAndAdvanceStatus` | `ApproveEstimate.shouldApproveEstimateSetEstimatedValue...` |
| Aprovar orçamento em `WAITING_APPROVAL` avança a ordem para `IN_PROGRESS` automaticamente | `ApproveEstimate.shouldApproveAndAdvanceStatus` | `ApproveEstimate.shouldApproveAndAdvanceStatus` |
| Orçamento já decidido não pode ser aprovado de novo → 409 | `ApproveEstimate.shouldReturn409WhenAlreadyDecided` | `ApproveEstimate.shouldThrowEstimateAlreadyDecidedException` |
| Ordem não pode ir para `IN_PROGRESS` sem orçamento aprovado | `UpdateStatus.shouldRejectApprovingWithoutApprovedEstimate` | `UpdateStatus.shouldThrowWhenStartingWithoutApprovedEstimate` |
| `/close` exige orçamento aprovado e status `IN_PROGRESS` | `Close.shouldReturn422WhenNotInProgress` | `Close.shouldThrow...WhenNotInProgress`, `...WhenThereIsNoApprovedEstimate` |
| `finalValue` opcional no fechamento — usa `estimatedValue` se omitido | `Close.shouldCloseUsingEstimatedValueWhenOmitted` | `Close.shouldCompleteUsingTheApprovedEstimateValueWhenFinalValueIsOmitted` |
| `finalValue` pode divergir do orçamento (serviço extra/desconto) | `Close.shouldCloseUsingProvidedFinalValue` | `Close.shouldUseTheProvidedFinalValueWhenPresent`, `...shouldRecordHistoryTransitionToCompletedWhenFinalValueDiffersFromTheEstimate` |
| Abertura e cada mudança de status enviam e-mail com link de acompanhamento | `PublicChannel.shouldEmailTrackingLinkOnOpeningAndOnEveryStatusChange` | `ProgressNotification.shouldInviteToTrackOnOpening`, `...shouldNotifyOnEveryStatusChange`, `...shouldNotifyOnClosing` |
| Link de acompanhamento vale trinta dias e não se gasta | `PublicChannel.shouldTrackThroughEmailedLink` | `WorkOrderTrackingTokenTest.Issue.issuesTokenValidForThirtyDays` |
| Acompanhamento expõe só o andamento do atendimento | `PublicChannel.shouldExposeOnlyTheProgressFields`, `...shouldTrackWorkOrderClosedByRejection` | `Track.shouldReturnTheTrackedWorkOrder` |
| Sem link, com link forjado (400) ou vencido (410), nada da OS é revelado | `PublicChannel.shouldNotRevealTheWorkOrderWithoutToken`, `...shouldReturn400ForForgedTrackingLink`, `...shouldReturn410WhenTrackingLinkHasExpired` | `Track.shouldRejectForgedTrackingLink`, `...shouldRejectExpiredTrackingLink` |
| Entrada em `WAITING_APPROVAL` reserva todas as peças do orçamento pendente | `EstimateDecisionAndStock.shouldReserveStockWhenAwaitingApproval` | `SendEstimateToCustomer.shouldReservePartsAndInviteCustomer` |
| Saldo insuficiente não reserva nada, não envia e-mail e mantém a OS em `DIAGNOSIS` | `EstimateDecisionAndStock.shouldKeepDiagnosisWhenInsufficientStock` | `SendEstimateToCustomer.shouldKeepDiagnosisWhenStockIsInsufficient` |
| Orçamento criado sobre OS já em `WAITING_APPROVAL` também reserva e é enviado | `PublicChannel.shouldReserveAndEmailReplacementEstimate` | — (exercitado ponta a ponta) |
| Entrada em `WAITING_APPROVAL` envia um link de aprovação e um de recusa | `PublicChannel.shouldEmailBothDecisionLinks` | `SendEstimateToCustomer.shouldIssueOneTokenPerDecision` |
| Aprovação pelo link leva a OS a `IN_PROGRESS` e mantém a reserva | `PublicChannel.shouldApproveThroughEmailedLink` | `DecideEstimate.shouldApproveAndKeepReservation` |
| Recusa pelo link devolve o estoque, conclui a OS e preenche `cancelledAt` | `PublicChannel.shouldRejectThroughEmailedLink` | `DecideEstimate.shouldRejectAndRestoreStock` |
| Link de decisão vale uma única vez → 410 | `PublicChannel.shouldReturn410WhenLinkIsReused` | `DecideEstimate.shouldConsumeTokenOnce`, `...shouldRejectReusedToken` |
| Link de decisão expira em sete dias → 410 | `PublicChannel.shouldReturn410WhenLinkHasExpired` | `DecideEstimate.shouldRejectExpiredToken` |
| Link forjado ou não emitido pela oficina → 400, sem alterar OS nem estoque | `PublicChannel.shouldReturn400ForForgedLink` | `DecideEstimate.shouldRejectTamperedToken`, `...shouldRejectUnknownToken` |
| Decidido o orçamento, o outro link deixa de valer → 409 | `PublicChannel.shouldReturn409WhenEstimateWasAlreadyDecided` | `DecideEstimate.shouldRejectSecondDecision` |
| Peça inexistente no orçamento → 404 | `CreateEstimate.shouldReturn404WhenPartNotFound` | `CreateEstimate.shouldThrowEstimatePartNotFoundException` |
| Itens de orçamento vazios são rejeitados → 400 | `CreateEstimate.shouldReturn400WhenItemsEmpty` | — (Bean Validation, não exercida no nível de serviço) |
| Mão de obra pode ser registrada múltiplas vezes na mesma ordem | `AddService.shouldAddService` | `AddService.shouldPersistALaborServiceLine` |
| Fila operacional agrupa por estágio e ordena pela OS mais antiga | `OperationalQueue.shouldGroupByWorkStage`, `...shouldPlaceTheOldestFirstWithinTheSameStatus` | `WorkOrderStatusTest.OperationalQueue.ordersFromTheMostAdvancedStage` |
| Fila operacional exclui OS concluídas, entregues e recusadas | `OperationalQueue.shouldExcludeClosedWorkOrders` | `WorkOrderStatusTest.OperationalQueue.excludesClosedStatuses` |
| Contagem e paginação consideram só o conjunto filtrado | `OperationalQueue.shouldCountOnlyTheQueuedWorkOrders`, `...shouldPaginateTheFilteredSet`, `...shouldReturnAnEmptyPageBeyondTheLastOne` | `FindOperationalQueue.shouldReturnPaginatedResponse` |

Cenários de validação de payload (`@Valid`/Bean Validation, ex.: descrição em branco, preço/valor
zero) só existem na camada de integração — só ali a requisição passa pelo filtro JAX-RS real antes
de chegar ao serviço.
