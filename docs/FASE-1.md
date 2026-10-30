# Fase 1 — A aplicação: API, domínio e uso local

Referência da aplicação em si: o que ela faz, como se configura, como autenticar, quais endpoints
existem, como a Ordem de Serviço evolui e como rodar os testes.

Foi o entregável da **Fase 1** (MVP em Docker Compose) e continua valendo por inteiro: a Fase 2 não
mudou regra de negócio nem contrato de API — levou a mesma aplicação para um cluster. Para
arquitetura, infraestrutura, deploy e escalabilidade, ver o [README](../README.md).

---

## Sumário

- [Funcionalidades](#funcionalidades)
- [Configuração](#configuração)
- [Autenticação JWT](#autenticação-jwt)
- [Mapa de endpoints](#mapa-de-endpoints)
- [Ciclo de vida da Ordem de Serviço](#ciclo-de-vida-da-ordem-de-serviço)
- [Testes automatizados e cobertura](#testes-automatizados-e-cobertura)
- [Build](#build)

---

## Funcionalidades

- **Ordem de Serviço (OS):** abertura, inclusão de serviços e de peças/insumos, **orçamento gerado
  automaticamente** (peças + mão de obra) e envio ao cliente para aprovação.
- **Acompanhamento:** ciclo de status `RECEIVED → DIAGNOSIS → WAITING_APPROVAL → IN_PROGRESS →
  COMPLETED → DELIVERED`, com **transições automáticas** conforme as ações. A recusa conclui a OS e
  preenche `cancelledAt`, sem criar um status adicional.
- **Canal público do cliente:** acompanhamento da OS e **aprovação/recusa do orçamento** por links
  assinados enviados por e-mail, sem login e sem consulta por id.
- **Gestão administrativa:** CRUD de clientes, veículos, serviços e peças; **controle de estoque**
  (reserva ao levar a OS a `WAITING_APPROVAL`, devolução na recusa do orçamento, recusa da operação
  quando o saldo é insuficiente e alerta de estoque mínimo).
- **Métrica:** tempo médio de execução das OS concluídas.
- **Segurança:** autenticação JWT (RS256) e RBAC nas APIs administrativas; validação de CPF/CNPJ e placa.

---

## Configuração

A tabela abaixo é o padrão **da aplicação**, que vale fora do Compose. Como subir o ambiente local
está em [Executando localmente](../README.md#executando-localmente).

| Variável | Descrição | Padrão |
|---|---|---|
| `INFRA_HOST_POSTGRES` | Host do PostgreSQL | `localhost` |
| `POSTGRES_DB` | Nome do **banco** | `oficina` |
| `POSTGRES_USERNAME` | Usuário do banco | `admin` |
| `POSTGRES_PASSWORD` | Senha do banco | `changeme` |
| `POSTGRES_SSLMODE` | Modo TLS da conexão | `disable` |
| `POSTGRES_TRUST_ALL` | Aceita o certificado do servidor sem validar a cadeia | `false` |
| `APP_SEED_ADMIN_USERNAME` / `APP_SEED_ADMIN_PASSWORD` | Admin inicial | `admin` / `admin123` |
| `APP_SEED_MECHANIC_USERNAME` / `APP_SEED_MECHANIC_PASSWORD` | Mecânico inicial | `mecanico` / `mecanico123` |
| `JWT_ISSUER` | Emissor gravado no token | `oficina-api` |
| `JWT_EXPIRATION_HOURS` | Validade do token (horas) | `8` |
| `JWT_PRIVATE_KEY_LOCATION` | Chave privada de assinatura RS256 | `.local-jwt/privateKey.pem` |
| `JWT_PUBLIC_KEY_LOCATION` | Chave pública de validação RS256 | `.local-jwt/publicKey.pem` |
| `SWAGGER_UI_ENABLED` | Publica o Swagger UI no artefato empacotado | `true` |
| `APP_PUBLIC_BASE_URL` | Base dos links enviados ao cliente por e-mail | `http://localhost:8080` |
| `MAIL_FROM` | Remetente dos e-mails ao cliente | `oficina@localhost` |
| `MAIL_HOST` / `MAIL_PORT` | Servidor SMTP | `localhost` / `1025` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Credenciais SMTP | vazio |
| `MAIL_START_TLS` | Política de STARTTLS na conexão SMTP | `DISABLED` |
| `MAIL_MOCK` | Só registra a mensagem em log, sem entregá-la | `true` |

Alguns desses padrões só valem quando a aplicação roda solta:

- **No Compose,** `INFRA_HOST_POSTGRES` é **fixado** para o container do banco
  (`oficina-mecanica-postgres`) e é o único valor que o `.env` não sobrescreve; as duas
  `JWT_*_KEY_LOCATION` passam a apontar para `/etc/jwt`, onde o volume do serviço `jwt-keys` é
  montado; e a senha do banco vira `admin`, não o `changeme` da tabela.
- **Sem servidor SMTP,** o padrão `MAIL_MOCK=true` mantém o fluxo utilizável: os e-mails ao cliente,
  com os links de acompanhamento e de decisão, só aparecem no log da aplicação.
- **No cluster,** quem entrega tudo são o `ConfigMap` e os dois `Secret` descritos em
  [Implantando no cluster](../README.md#implantando-no-cluster). Lá o RDS exige TLS, então
  `POSTGRES_SSLMODE` é `require` e `POSTGRES_TRUST_ALL` é `true` — a CA da AWS não está no truststore
  da JVM.

> **Banco x Schema:** o **banco** chama-se `oficina` e a aplicação cria/usa o **schema**
> `oficina_mecanica` automaticamente na inicialização (Flyway). Não confunda os dois.
>
> **Senhas de seed:** via `docker compose` já vêm preenchidas (`admin123` / `mecanico123`). Se você
> rodar fora do Compose com `APP_SEED_*_PASSWORD` em branco, a aplicação **gera uma senha aleatória**
> no primeiro boot e a imprime no log com o prefixo `[SEED]`.

---

## Autenticação JWT

As APIs administrativas usam **Bearer JWT (RS256)**: login → token → header `Authorization`.

| Usuário | Role | Senha (padrão do Compose) |
|---|---|---|
| `admin` | `ADMIN` | `admin123` |
| `mecanico` | `MECHANIC` | `mecanico123` |

### 1. Login

```shell
curl -s -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Resposta:

```json
{ "token": "eyJraWQiOiJ...", "username": "admin", "role": "ADMIN", "expiresIn": 28800 }
```

### 2. Usando o token

```shell
TOKEN="eyJraWQiOiJ..."
curl -s http://localhost:8080/v1/customer -H "Authorization: Bearer $TOKEN"
```

### 3. Pelo Swagger UI

Abra `http://localhost:9000/q/swagger-ui` → **Authorize** (esquema `bearerAuth`) → cole **apenas o
token** (sem `Bearer`) → as chamadas passam a enviar o header automaticamente.

> Sem senha definida no `.env`? Recupere a gerada no log:
> `docker compose logs app | grep SEED`

### Chaves JWT (RS256)

O token é assinado com RSA. **A imagem não contém chave privada e o repositório não versiona nenhuma
chave:** o par é gerado uma única vez, fora do build, e informado à aplicação por
`JWT_PRIVATE_KEY_LOCATION` / `JWT_PUBLIC_KEY_LOCATION`. Assim a mesma imagem serve para qualquer
ambiente e um rebuild não invalida os tokens em circulação.

| Ambiente | Origem do par |
|---|---|
| `docker compose` | Serviço `jwt-keys` gera na primeira subida e guarda no volume `jwt_keys`, montado em `/etc/jwt` |
| Dev mode e uso local | `infra/scripts/generate-jwt-pair.sh` cria `.local-jwt/` (fora do controle de versão) |
| Testes de integração | Par efêmero gerado por `JwtKeyPairTestResource` em `target/jwt-test/` |
| Cluster | `Secret` `oficina-mecanica-jwt`, montado em `/etc/jwt`, criado pelo caminho de entrega |

Guarde a chave privada apenas no gerenciador de secrets — nunca no repositório, no `.env` versionado
ou na imagem. Regerar o par invalida todos os tokens já emitidos, então trate-o como rotação
planejada. Sem `JWT_PRIVATE_KEY_LOCATION` definido, o `JwtKeyStartupGuard` **bloqueia a inicialização
em produção**, para que nenhum ambiente produtivo suba com a chave de desenvolvimento.

Como gravar o par nos secrets do repositório, para o cluster:
[Pelo caminho de entrega](../README.md#pelo-caminho-de-entrega).

---

## Mapa de endpoints

Base local: `http://localhost:8080`. No cluster, o hostname do ELB. Papéis: 🔓 público ·
🔧 ADMIN+MECHANIC · 🛡️ ADMIN.

> **Modelo de acesso (segregação de funções):** os **cadastros/dados-mestre** (clientes, veículos,
> serviços e peças) são **escritos apenas pelo ADMIN**; o MECHANIC pode **consultá-los** (GET) mas não
> criar/editar/excluir. As **Ordens de Serviço** (abrir, mudar status, lançar serviço, orçamento,
> fechar) são operadas por **ADMIN e MECHANIC**. Os **workers** são a exceção: são dados de pessoal, e
> o `WorkerController` é `ADMIN` inteiro — nem a leitura é liberada ao MECHANIC. O ADMIN é
> superusuário (faz tudo).

### Autenticação — `/v1/auth`
| Método | Path | Acesso |
|---|---|---|
| POST | `/v1/auth/login` | 🔓 |

### Clientes — `/v1/customer`
| Método | Path | Acesso |
|---|---|---|
| GET | `/v1/customer` | 🔧 |
| GET | `/v1/customer/{id}` | 🔧 |
| GET | `/v1/customer/by-document/{document}` | 🔧 |
| POST | `/v1/customer` | 🛡️ |
| PUT | `/v1/customer/{id}` | 🛡️ |
| DELETE | `/v1/customer/{id}` | 🛡️ |

### Veículos — `/v1/vehicle`
| Método | Path | Acesso |
|---|---|---|
| GET | `/v1/vehicle` (filtros: `license_plate`, `manufacturer`, `model`) | 🔧 |
| GET | `/v1/vehicle/{id}` | 🔧 |
| GET | `/v1/vehicle/by-license-plate/{license_plate}` | 🔧 |
| POST | `/v1/vehicle` | 🛡️ |
| PUT | `/v1/vehicle/{id}` | 🛡️ |
| DELETE | `/v1/vehicle/{id}` | 🛡️ |

### Trabalhadores — `/v1/worker`
| Método | Path | Acesso |
|---|---|---|
| GET | `/v1/worker` · `/v1/worker/{id}` | 🛡️ |
| POST | `/v1/worker` | 🛡️ |
| POST | `/v1/worker/login` | 🔓 |
| PUT | `/v1/worker/{id}` | 🛡️ |
| DELETE | `/v1/worker/{id}` | 🛡️ |

### Catálogo de serviços — `/admin/services`
| Método | Path | Acesso |
|---|---|---|
| GET | `/admin/services` · `/admin/services/{id}` | 🔧 |
| POST | `/admin/services` | 🛡️ |
| PUT | `/admin/services/{id}` | 🛡️ |
| DELETE | `/admin/services/{id}` | 🛡️ |

### Peças e insumos — `/admin/parts`
| Método | Path | Acesso |
|---|---|---|
| GET | `/admin/parts` · `/admin/parts/{id}` · `/admin/parts/low-stock` | 🔧 |
| POST | `/admin/parts` | 🛡️ |
| PUT | `/admin/parts/{id}` | 🛡️ |
| PATCH | `/admin/parts/{id}/stock?adjustment=±N` | 🛡️ |
| DELETE | `/admin/parts/{id}` | 🛡️ |

### Ordens de serviço — `/v1/work-orders`
| Método | Path | Acesso |
|---|---|---|
| GET | `/v1/work-orders` (`page`, `size`; `q` e `sort` do contrato de paginação são ignorados aqui) | 🔧 |
| GET | `/v1/work-orders/{id}` | 🔧 |
| GET | `/v1/work-orders/metrics/average-execution-time` | 🛡️ |
| POST | `/v1/work-orders` | 🔧 |
| POST | `/v1/work-orders/{id}/services` | 🔧 |
| POST | `/v1/work-orders/{id}/estimate` | 🔧 |
| PATCH | `/v1/work-orders/{id}/status` | 🔧 |
| PATCH | `/v1/work-orders/{id}/estimate/{estimateId}/approve` | 🔧 |
| PATCH | `/v1/work-orders/{id}/estimate/{estimateId}/reject` | 🔧 |
| PATCH | `/v1/work-orders/{id}/close` | 🔧 |

### Canal público do cliente — `/v1/public/work-orders`
| Método | Path | Acesso |
|---|---|---|
| GET | `/v1/public/work-orders/tracking/{token}` | 🔓 |
| POST | `/v1/public/work-orders/estimate-decisions/{token}` | 🔓 |

### Operação — `/q`
| Método | Path | Acesso |
|---|---|---|
| GET | `/q/health/live` · `/q/health/ready` · `/q/health/started` | Interface interna de gerenciamento na porta 9000 |
| GET | `/q/openapi` · `/q/swagger-ui` | Interface interna de gerenciamento na porta 9000 |

A collection que exercita todos eles em ordem está em [postman/](../postman/README.md).

---

## Ciclo de vida da Ordem de Serviço

```
RECEIVED ─► DIAGNOSIS ─► WAITING_APPROVAL ─► IN_PROGRESS ─► COMPLETED ─► DELIVERED
                                   │
                                   └─(rejeição)─► COMPLETED + cancelledAt
```

- A OS nasce em **`RECEIVED`** (status definido automaticamente na criação).
- Gerar o orçamento move `DIAGNOSIS → WAITING_APPROVAL` — é essa entrada que **reserva as peças no
  estoque** e envia ao cliente os links de decisão. Aprovar move `→ IN_PROGRESS` e mantém a reserva;
  recusar devolve as peças.
- `COMPLETED` é alcançado por `PATCH /{id}/close` ou pela recusa de um orçamento pendente.
- A recusa preenche `closedAt` e `cancelledAt`; não existe cancelamento manual nem status
  `CANCELLED`.
- OS `DELIVERED` ou concluída por recusa ficam bloqueadas.

Detalhes em [WORKORDER.md](../WORKORDER.md); o modelo de dados, em
[MODELO-RELACIONAL.md](MODELO-RELACIONAL.md).

---

## Testes automatizados e cobertura

```shell
./mvnw test            # unitários (*Test.java), sem dependências externas
./mvnw test -Pitest    # + integração (*IT.java) com Testcontainers — requer Docker
./mvnw verify          # testes + cobertura (JaCoCo) + análise estática
```

O gate do JaCoCo exige **80% de linhas cobertas no bundle**; relatório em
`target/jacoco-report/index.html`. Os `*IT` sobem `@QuarkusTest` contra um Postgres real e cobram os
fluxos críticos ponta a ponta: ciclo de vida da OS, orçamento, estoque, autenticação e RBAC, mais o
mapeamento de cada contexto.

Acima desses, um único seam verifica o **ambiente implantado**: `infra/scripts/smoke-test.sh`. Ele não
verifica regra de negócio — isso os `*IT` já fazem —, e por isso uma falha ali aponta para
infraestrutura.

`./mvnw verify` também executa Spotless, Checkstyle, PMD e SpotBugs. As varreduras de segurança da
esteira de CI e a postura do ambiente implantado estão em
[Análise estática e segurança](../README.md#análise-estática-e-segurança); o resultado consolidado, em
[RELATORIO-VULNERABILIDADES.md](RELATORIO-VULNERABILIDADES.md).

---

## Build

Para o cluster, quem constrói a imagem é `infra/scripts/publish-image.sh` (ou o caminho de entrega).
Fora dele, direto do Maven:

```shell
# JAR
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Nativo (GraalVM ou container)
./mvnw package -Dnative
./mvnw package -Dnative -Dquarkus.native.container-build=true
```
