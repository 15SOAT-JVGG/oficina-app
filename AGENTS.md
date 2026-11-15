# AGENTS.md

## O projeto

Back-end do **Sistema Integrado de Atendimento e Execução de Serviços** de uma oficina mecânica
(FIAP 16SOAT — Tech Challenge). Expõe uma API REST para ordens de serviço, clientes, veículos e
equipe, além de um canal público de acompanhamento. Catálogo e orçamento pertencem ao Billing;
estoque pertence ao Execution. A abertura da OS envia eventos ao Billing e mostra a projeção
assíncrona do orçamento.

### Stack

| Item | Versão / Tecnologia |
|---|---|
| Linguagem | Java 21 |
| Framework | Quarkus 3.32.3 (`quarkus-bom`) |
| Build | Maven (usar o wrapper `./mvnw`) |
| Persistência | Hibernate Reactive + Panache, PostgreSQL 16 |
| Migrations | Flyway (`src/main/resources/db/migration`) |
| Programação assíncrona | Mutiny (`Uni<T>`) |
| Segurança | SmallRye JWT (RS256) + RBAC por roles |
| Documentação | SmallRye OpenAPI / Swagger UI |
| Boilerplate | Lombok |
| Testes | JUnit 5, Mockito, REST-assured, Testcontainers |
| Cobertura | JaCoCo 0.8.14 |
| Qualidade estática | Checkstyle, PMD, SpotBugs, Spotless |

### Comandos

```shell
./mvnw quarkus:dev                  # dev mode (requer: docker compose up -d postgres)
./mvnw test                         # testes unitários (*Test.java)
./mvnw test -Pitest                 # unitários + integração (*IT.java)
./mvnw verify                       # build completo: qualidade estática + cobertura
docker compose up                   # sobe aplicação + banco
```

Rodar `./mvnw verify` antes de considerar qualquer tarefa concluída: ele executa Checkstyle, PMD,
SpotBugs, Spotless e o gate de cobertura do JaCoCo.

---

## Arquitetura Hexagonal

O código é organizado por **bounded context** e, dentro de cada um, por camada hexagonal.
Contextos atuais: `auth`, `customer`, `vehicle`, `worker`, `workorder`,
mais o `shared` com utilitários transversais.

```
br.com.fiap.postech.soat16.fase1.<contexto>
├── domain/                 # regras de negócio puras
│   ├── model/              # agregados, entidades, value objects, enums
│   └── exception/          # exceções de negócio + ErrorCode
├── application/            # orquestração de casos de uso
│   ├── <Contexto>Service   # o caso de uso em si
│   ├── command/            # entrada dos casos de uso (records)
│   ├── result/             # saída dos casos de uso (records)
│   ├── mapper/             # domínio <-> result
│   └── port/out/           # interfaces que a aplicação exige do mundo externo
└── adapter/
    ├── in/rest/            # controller, dto/request, dto/response, mapper, openapi
    └── out/                # persistence (entity/mapper/repository), security, notification
```

Regras de dependência, verificadas automaticamente por
`architecture/HexagonalDependencyArchitectureTest` e pelos `*ArchitectureTest` de cada contexto:

- `domain` depende apenas de si mesmo e do `shared/domain`.
- `application` depende de `domain` e das suas próprias portas.
- `adapter` depende de `application` e `domain`, implementando as portas.

Ao adicionar integração com algo externo (banco, HTTP, fila, e-mail), declare uma porta em
`application/port/out` e implemente-a em `adapter/out`. Quando um contexto precisa consultar outro,
faça-o por uma porta de lookup dedicada — o padrão está em `CustomerVehicleLookupPort` /
`VehicleLookupAdapter`.

Mantenha o `Service` como orquestrador fino: ele carrega o agregado, delega a decisão de negócio ao
domínio e persiste o resultado. Retorne sempre `Uni<T>` nas camadas reativas e anote os métodos com
`@WithSession` (leitura) ou `@WithTransaction` (escrita).

---

## DDD

- Coloque a regra de negócio no agregado, não no service. `WorkOrder` decide as transições de
  status válidas (`transitionTo`, `close`, `ensureMutable`). Orçamentos e saldo de estoque são
  responsabilidade dos serviços Billing e Execution.
- Crie agregados por factory method estático nomeado pela intenção (`Customer.create(...)`,
  `WorkOrder.open(...)`) e mude estado por métodos de comportamento (`approve()`, `close()`).
- Use value objects para conceitos com invariante própria e valide-os na criação. `Document.of(...)`
  é a referência: valida CPF/CNPJ, deriva o `DocumentType` e é imutável.
- Lance exceções de negócio específicas do domínio, derivadas de `BusinessException` /
  `ResourceNotFoundException`, com um `ErrorCode` do contexto — assim o `AppExceptionMapper`
  traduz para a resposta HTTP correta.
- Baseie `equals`/`hashCode` de entidade na identidade persistida, como em `Customer`.
- Use a linguagem do negócio nos nomes: `WorkOrder`, `EstimateProjection`, `Customer`, `Worker`.
  Em português apenas o texto voltado a pessoas (`@DisplayName`, mensagens, documentação).

---

## Clean Code

- Nomeie revelando intenção, com o vocabulário do domínio: `estimateTotal`, `licensePlate`,
  `awaitingApprovalWorkOrders`. Prefira o nome completo a abreviações.
- Nomeie booleanos como afirmação: `documentExists`, `hasVehicles`, `isActive`.
- Nomeie métodos pela ação de negócio: `approveEstimate`, `adjustStock`, `findByDocument`.
- Nomeie testes descrevendo o comportamento esperado e complemente com `@DisplayName` em português.
- Extraia constantes nomeadas para literais repetidos (`CPF_LENGTH`, `WORK_ORDERS_PATH`).
- Mantenha o método com um único nível de abstração e retorne cedo nos casos de guarda.
- Reserve o comentário para explicar o *porquê* de uma decisão não óbvia, como o comentário sobre
  identidade em `Customer.equals`.
- Siga a ordem de imports do Spotless: `java`, `javax`, `jakarta`, `org`, `com`, `br`, demais.
  Limite de 140 colunas, indentação de 4 espaços, imports explícitos (sem `*`).

---

## Testes

### Cobertura mínima de 80%

O gate do JaCoCo em `pom.xml` (execução `check`) precisa passar com **no mínimo 80% de linhas
cobertas no bundle**. Toda entrega inclui os testes que sustentam esse número.

Relatório: `target/jacoco-report/index.html` após `./mvnw verify`.

Cubra, para cada classe nova ou alterada:

- o caminho de sucesso;
- cada regra de negócio e cada invariante do domínio, incluindo as violações que lançam exceção;
- cada ramo condicional (`if`, `switch`, `Optional`/`Uni` vazio, transição de status inválida).

### Convenções

| Sufixo | Escopo | Execução |
|---|---|---|
| `*Test.java` | Unitário, dependências mockadas com Mockito | `./mvnw test` |
| `*IT.java` | Integração, `@QuarkusTest` + Postgres real via Testcontainers | `./mvnw test -Pitest` |

Espelhe o pacote da classe sob teste, use JUnit 5 com `@Nested` para agrupar cenários e
`@DisplayName` em português para descrever o comportamento.

Testes de migração que precisam preparar um schema legado antes da inicialização do Quarkus usam
Testcontainers e Flyway diretamente, mantêm o sufixo `*IT` e agrupam os cenários com `@Nested`.

### Testes de integração nos fluxos críticos

Todo fluxo crítico tem cobertura ponta a ponta via HTTP com REST-assured e banco real
(`@QuarkusTest` + `@QuarkusTestResource(PostgresTestResource.class)`), exercitando controller,
service, domínio, persistência e migrations juntos. `WorkOrderControllerIT` é a referência.

Fluxos que exigem teste de integração:

1. **Abertura assíncrona:** OS, projeção inicial e evento outbox são gravados na mesma transação;
   o resultado do Billing entra pela inbox e só atualiza uma projeção ainda aguardando.
2. **Mensageria:** publicação SNS FIFO com retentativa e ordem por OS; consumo SQS FIFO confirma
   a mensagem somente após commit. Integração local usa Postgres e LocalStack.
3. **Transição de legado:** fluxos locais de orçamento e estoque respondem `503` até os respectivos
   serviços oferecerem as integrações. Teste as respostas e a autorização desses endpoints.
4. **Autenticação e autorização:** login, emissão de JWT RS256 e RBAC nas rotas administrativas
   (`AuthControllerIT`, `SecurityIT`).
5. **Persistência de cada contexto:** os `*RepositoryIT` validam mapeamento JPA, constraints de
   unicidade e migrations.

Ao criar um endpoint, cubra no `*IT` o status HTTP de sucesso, o payload de resposta e os erros
esperados (`400`, `401`, `403`, `404`, `409`), afirmando sobre o `ApiErrorResponseDto`.

---

## Agent skills

### Issue tracker

Issues e specs vivem no GitHub Issues do repositório, operados pela CLI `gh`.
Ver `docs/agents/issue-tracker.md`.

### Domain docs

Repositório single-context: `CONTEXT.md` na raiz e ADRs em `docs/adr/`.
Ver `docs/agents/domain.md`.
