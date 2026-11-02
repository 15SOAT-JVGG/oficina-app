# Ciclo canônico da Ordem de Serviço

> Para as decisões planejadas da Fase 4, veja o [ADR-0008](0008-fronteiras-e-saga-da-fase-4.md). Este registro preserva a decisão anterior.

A Ordem de Serviço usa apenas `RECEIVED`, `DIAGNOSIS`, `WAITING_APPROVAL`, `IN_PROGRESS`,
`COMPLETED` e `DELIVERED`. A recusa do orçamento é o único cancelamento: ela conclui a OS, preenche
`cancelledAt` e bloqueia novas alterações, sem introduzir um status próprio.

Os registros legados em `APPROVED` são migrados para `IN_PROGRESS`; os registros em `CANCELLED`,
para `COMPLETED` com `cancelledAt`. Os valores anteriores permanecem em colunas de auditoria e o
rollback manual em `db/rollback/U2__migrate_work_order_status_lifecycle.sql` restaura o contrato
anterior se a reversão for necessária.
