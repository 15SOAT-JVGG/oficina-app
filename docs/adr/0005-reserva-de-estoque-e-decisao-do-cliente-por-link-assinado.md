# Reserva de estoque na entrada em WAITING_APPROVAL e decisão do cliente por link assinado

> Para as decisões planejadas da Fase 4, veja o [ADR-0008](0008-fronteiras-e-saga-da-fase-4.md). Este registro preserva a decisão anterior.

A reserva de peças passa a acontecer na entrada em `WAITING_APPROVAL`, e não mais na aprovação do
orçamento. Enviar um orçamento é o momento em que a oficina se compromete com um preço e um prazo,
e prometer o que não há em estoque é a falha que a mudança evita: se qualquer peça faltar, nada é
reservado, nenhum e-mail sai e a ordem permanece em `DIAGNOSIS`. A aprovação apenas confirma a
reserva já feita; a recusa a devolve ao estoque.

A decisão do cliente deixa de ser um `PATCH` público por id e passa a ser um `POST` em
`/v1/public/work-orders/estimate-decisions/{token}`, com um `EstimateDecisionToken` por decisão
enviado por e-mail. As rotas públicas anteriores foram removidas: elas aceitavam qualquer id de
orçamento conhecido, então qualquer pessoa podia decidir pelo cliente.

A assinatura RS256 prova apenas que o link saiu da oficina; quem garante o uso único é o registro
do token no banco, porque uma assinatura continua válida a cada vez que é apresentada. Por isso o
`exp` do JWT é muito mais longo que a janela de sete dias: um link vencido precisa alcançar o
registro para responder "expirado" (410), em vez de morrer antes disso como se fosse forjado (400).

O verbo é `POST`, e não `GET`, porque o token vale uma única vez e um cliente de e-mail que
pré-carrega os links da mensagem consumiria a decisão sem que o cliente a tomasse.
