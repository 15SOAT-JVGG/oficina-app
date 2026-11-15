# Modelo Relacional — OS

Modelo atual do serviço OS no schema `oficina_mecanica`. O catálogo não pertence mais a este
serviço: a migration `V5__remove_local_catalog.sql` remove `parts` e `service_item` e as FKs que os
referenciavam. IDs presentes nas antigas tabelas `estimate_item` e `work_order_service` são mantidos
como histórico, sem FK nem leitura pela aplicação.

## Diagrama ER

```mermaid
erDiagram
    CUSTOMER ||--o{ VEHICLE : possui
    CUSTOMER ||--o{ WORK_ORDER : solicita
    VEHICLE ||--o{ WORK_ORDER : "é objeto de"
    WORKER ||--o{ WORK_ORDER : "atende (opcional)"
    WORK_ORDER ||--o{ WORK_ORDER_HISTORY : registra
    WORK_ORDER ||--|| ESTIMATE_PROJECTION : "acompanha orçamento"
    WORK_ORDER_OUTBOX ||--|| ESTIMATE_PROJECTION : "evento de abertura"

    CUSTOMER {
        uuid customer_id PK
        string document UK
        string first_name
        string last_name
        string email
        string phone_number
        boolean active
    }
    VEHICLE {
        uuid vehicle_id PK
        uuid customer_id FK
        string license_plate UK
        string manufacturer
        string model
        int year
        long km_driven
        enum type
    }
    WORKER {
        uuid worker_id PK
        enum profile
        string first_name
        string last_name
        string email
        boolean active
    }
    WORK_ORDER {
        uuid work_order_id PK
        uuid customer_id FK
        uuid vehicle_id FK
        uuid assigned_worker_id FK
        string description
        enum priority
        enum status
        datetime opened_at
        datetime closed_at
        datetime cancelled_at
        decimal estimated_value
        decimal final_value
    }
    WORK_ORDER_HISTORY {
        uuid work_order_history_id PK
        uuid work_order_id FK
        enum previous_status
        enum new_status
        datetime changed_at
    }
    WORK_ORDER_OUTBOX {
        bigint sequence_id PK
        uuid event_id UK
        uuid work_order_id
        string event_type
        jsonb payload
        string traceparent
        datetime created_at
        datetime published_at
    }
    ESTIMATE_PROJECTION {
        uuid work_order_id PK
        uuid opening_event_id FK
        uuid saga_id
        uuid correlation_id
        enum status
        uuid estimate_id
        jsonb estimate_data
        string failure_code
        string failure_message
    }
    WORK_ORDER_INBOX {
        uuid event_id PK
        uuid work_order_id
        string event_type
        datetime received_at
    }
```

`work_order_outbox`, `work_order_estimate_projection` e `work_order_inbox` são criadas pela
migration V4. A outbox guarda `workOrderId` sem FK para a OS, pois o Flyway executa antes da criação
da tabela Hibernate em um banco vazio. A aplicação grava a OS, a projeção e o evento dentro da mesma
transação reativa.

`estimate_item` e `work_order_service` são tabelas históricas que podem existir após a V5. A migration
remove suas FKs para as tabelas do catálogo e mantém os dados legados; não há entidades, repositórios
ou endpoints OS para essas tabelas. A projeção do orçamento guarda o payload de Billing em JSONB e
não consulta o banco do Billing.

## Enums

| Enum | Valores |
|---|---|
| `DocumentType` | CPF, CNPJ |
| `VehicleType` | CAR, MOTORCYCLE |
| `WorkerProfile` | MECHANIC, ATTENDANT |
| `WorkOrderPriority` | LOW, MEDIUM, HIGH, URGENT |
| `WorkOrderStatus` | RECEIVED, DIAGNOSIS, WAITING_APPROVAL, IN_PROGRESS, COMPLETED, DELIVERED |
| Projeção de orçamento | `AGUARDANDO`, `CRIADO`, `FALHOU`; sem solicitação inicial o status fica nulo |

O diagrama representa relações garantidas por FK ou pela transação de escrita. `work_order_id` da
outbox e `work_order_id` da inbox são metadados da integração, sem FK entre bounded contexts.
