# Entrega e operação

Runbook de como a aplicação chega ao cluster e como operá-la depois: scripts, ambientes, segredos,
smoke test e demonstração do HPA.

O cluster e o banco **não** nascem aqui. Cada um tem o seu repositório e a sua pipeline:

| Repositório | Provisiona |
|---|---|
| `oficina-infra-k8s` | Cluster EKS `oficina`, node group, ECR `oficina-app` e `metrics-server` |
| `oficina-infra-db` | RDS PostgreSQL 16, um por ambiente |
| `oficina-lambda-auth` | Lambda de autenticação por CPF |

A conta é do AWS Academy (Learner Lab), e isso molda a solução: não há `iam:CreateRole`, a rede e as
roles vêm prontas, e as credenciais da sessão expiram em cerca de quatro horas. As decisões que
decorrem disso estão nos ADRs em [`docs/adr/`](../docs/adr/).

```
infra/
├── docker/     Dockerfile da aplicação
└── scripts/    publicação da imagem, chaves, credenciais, acesso da pipeline, smoke test e carga
    └── lib/    o que smoke test e carga compartilham: ferramentas e endereço do ambiente
k8s/            manifestos: base/ e um overlay por ambiente (homolog, production)
```

## Ambientes

Há dois, `homolog` e `production`, no **mesmo cluster**, isolados por namespace e por um RDS próprio
cada. A `main` é só integração: nada é implantado a partir dela. O deploy acontece no push em
`homolog` ou `production`, que são promovidos por pull request a partir da `main`.

| Branch | Namespace | Overlay | Banco |
|---|---|---|---|
| `homolog` | `homolog` | `k8s/overlays/homolog` | `oficina-homolog` |
| `production` | `production` | `k8s/overlays/production` | `oficina-production` |

O overlay de `production` carrega um `PREENCHER` no host do banco até o RDS existir; o workflow
recusa o deploy enquanto ele estiver lá.

## Habilitando um ambiente (uma vez)

Com cluster e banco já provisionados e um kubeconfig de administrador:

```bash
aws eks update-kubeconfig --name oficina --region us-east-1

# Cria o namespace, a ServiceAccount da pipeline (com permissão só nele) e grava o kubeconfig dela.
infra/scripts/create-deploy-kubeconfig.sh homolog
```

O script imprime ao fim o `gh secret set` que guarda o kubeconfig no Environment. Depois, no
Environment correspondente do GitHub, os secrets que o deploy consome:

| Secret | De onde vem |
|---|---|
| `KUBE_CONFIG_B64` | `infra/scripts/create-deploy-kubeconfig.sh <ambiente>` |
| `POSTGRES_USERNAME` / `POSTGRES_PASSWORD` | `db_username` e a senha do RDS do ambiente, em `oficina-infra-db` |
| `APP_SEED_ADMIN_PASSWORD` / `APP_SEED_MECHANIC_PASSWORD` | escolhidas pelo operador; são as credenciais de acesso à API |
| `JWT_PRIVATE_KEY_B64` / `JWT_PUBLIC_KEY_B64` | `infra/scripts/generate-jwt-pair.sh` |
| `AUTHORIZER_TEST_CUSTOMER_CPF` | CPF sintético de cliente ativo para o smoke test Gateway em homolog; configure também no Environment `homolog` da Lambda |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | conta SMTP; opcionais, mas as duas andam juntas |

Em `homolog`, os secrets Organization `HOMOLOG_AWS_ACCESS_KEY_ID`, `HOMOLOG_AWS_SECRET_ACCESS_KEY` e
`HOMOLOG_AWS_SESSION_TOKEN` são limitados aos quatro repositórios e renovados a cada sessão do lab.
Production continua usando `AWS_*` no Environment. `AWS_REGION` e `TF_STATE_BUCKET` são variables da
Organization; variables de Environment com os mesmos nomes têm precedência.

Nenhum deles aparece em log: o GitHub redige valor de secret na saída, e `create-app-credentials.sh`
monta os `Secret` por `kubectl create ... --dry-run=client -o yaml | kubectl apply -f -`, sem echo.

O token da `ServiceAccount` é de longa duração e concede acesso ao namespace. Se vazar, não expira
sozinho: `kubectl delete serviceaccount github-deployer -n <ambiente>` e gere outro.

## Pelo caminho de entrega

O workflow **Deploy** (`.github/workflows/deploy.yml`) roda no push em `homolog` ou `production`.
São três jobs encadeados, e a etapa que falha interrompe a entrega — teste vermelho não gera imagem, e
imagem que não publicou não chega ao cluster.

| Job | O que faz | Credencial |
|---|---|---|
| Build & Tests | Confere o ambiente e roda `./mvnw verify`: build, testes, qualidade estática e cobertura | nenhuma |
| Imagem no ECR | Publica a imagem com a tag do SHA do commit | sessão do lab |
| Deploy no cluster | Entrega os `Secret`, aplica o overlay com a tag do commit, aguarda o rollout e roda o smoke test | token da `ServiceAccount` |

A tag da imagem é o SHA do commit, e é ela que faz do `kubectl apply` um rollout de verdade: com
`latest`, o objeto no cluster não mudaria e nenhum pod seria substituído. Por isso o job troca a tag
com `kustomize edit set image` antes de aplicar.

As migrations não têm passo próprio. `flyway.migrate-at-start` as aplica na inicialização, então quem
as espera é o `kubectl rollout status`: um pod só fica pronto depois de migrar o banco. E o último
passo é o smoke test.

**O acesso ao cluster não usa credencial da AWS** ([ADR-0002](../docs/adr/0002-deploy-no-cluster-via-kubeconfig-de-serviceaccount.md)).
A sessão do lab expira, e o deploy ficaria vermelho por motivo alheio ao código.

Se o rollout ou o smoke test falharem, o job colhe `kubectl get pods`, o `describe` do `Deployment` e
as últimas cem linhas de log da aplicação.

## Da máquina do operador

O mesmo deploy, à mão, para um ambiente:

```bash
export NAMESPACE=homolog
export POSTGRES_USERNAME=... POSTGRES_PASSWORD=...
export APP_SEED_ADMIN_PASSWORD=... APP_SEED_MECHANIC_PASSWORD=...

infra/scripts/generate-jwt-pair.sh              # só na primeira vez; o par vale para os deploys seguintes
infra/scripts/create-app-credentials.sh
infra/scripts/publish-image.sh                  # tag = SHA do commit atual
```

Depois, aplique o overlay trocando o placeholder da imagem pela que foi publicada. O
`kustomize edit set image` altera o `kustomization.yaml`, então rode-o numa cópia — no repositório
essa alteração não deve ser commitada:

```bash
ECR_URL="$(aws ecr describe-repositories --repository-names oficina-app --query 'repositories[0].repositoryUri' --output text)"
WORK_DIR="$(mktemp -d)" && cp -R k8s "$WORK_DIR/"
(cd "$WORK_DIR/k8s/overlays/homolog" \
  && kustomize edit set image "oficina-mecanica=$ECR_URL:$(git rev-parse HEAD)" \
  && kubectl apply -k .)
kubectl rollout status deploy/oficina-mecanica -n homolog
```

Reentregar o mesmo commit (depois de trocar um segredo, por exemplo) não muda o `Deployment`, e os
pods continuariam com os segredos antigos: nesse caso rode também
`kubectl rollout restart deploy/oficina-mecanica -n homolog`. O workflow faz isso sozinho.

O endereço público sai do `Service`; o ELB leva cerca de um minuto para começar a responder:

```bash
ELB="$(kubectl get svc oficina-mecanica -n homolog -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')"
curl "http://$ELB/q/health/ready"
```

Os dois `Secret` não estão em `k8s/` de propósito — senha do banco, credenciais de seed e chave
privada não entram no repositório. `create-app-credentials.sh` os monta a partir das variáveis acima e
diz **qual** secret está inválido quando um base64 chega malformado.

## Smoke test

Um comando responde se o deploy funcionou. Ele descobre o endereço do `Service` sozinho — esperando o
ELB nascer, se for o caso —, espera o readiness, autentica com o usuário de seed, abre uma ordem de
serviço e a recupera na listagem. Isso exercita de uma vez o ELB, o pod, o `ConfigMap`, os dois
`Secret`, a conectividade com o RDS e as migrations. Sai com código diferente de zero na primeira
verificação que falhar.

```bash
export APP_SEED_ADMIN_PASSWORD=...       # a mesma senha entregue no Secret
NAMESPACE=homolog infra/scripts/smoke-test.sh       # ou: infra/scripts/smoke-test.sh http://localhost:8080
```

Regra de negócio ele não verifica — isso é o que os `*IT` da aplicação já fazem contra um Postgres
real, e por isso uma falha aqui aponta para infraestrutura. Requer `curl` e `jq`, mais `kubectl`
quando o endereço não é informado.

Duas coisas que só aparecem contra o RDS e valem lembrar:

- **TLS é obrigatório.** O Postgres 16 no RDS recusa conexão em claro, então o `ConfigMap` manda
  `POSTGRES_SSLMODE=require`. O default da aplicação é `disable`, que é o que o Postgres do
  docker compose fala.
- **As migrations sobem sozinhas.** `V1__create_schema.sql` cria o schema `oficina_mecanica` e o
  Flyway roda na inicialização, então o primeiro deploy converge contra um banco vazio.

## Escalabilidade automática

A aplicação ganha réplicas quando a carga sobe e as devolve quando a carga cai, sem intervenção. Quem
decide é o `HorizontalPodAutoscaler` de `k8s/base/hpa.yaml`, que compara o consumo dos pods com os
`requests` declarados no `Deployment` — daí a exigência de declará-los.

| Ajuste | Valor | Por quê |
|---|---|---|
| Réplicas | de 1 a 6 | Uma no vale deixa o Flyway migrar sem concorrência; seis cabem nos dois nós `t3.medium` sem depender de nós novos |
| CPU | 60% do request de 250m | É a métrica que manda na prática: a verificação BCrypt do login é cara em processador |
| Memória | 80% do request de 512Mi | Rede de segurança para carga que pese no heap; o pod ocioso fica perto de 250Mi, bem abaixo do alvo |
| Subida | imediata, até 2 pods a cada 15s | O default já é imediato; o passo maior encurta a demonstração |
| Descida | 1 pod a cada 30s, após 1 min de calmaria | O default espera 5 minutos, o que não cabe num vídeo de 15 |

A fonte das métricas é o `metrics-server`, instalado pelo `oficina-infra-k8s` como addon do EKS. Sem
ele o HPA lê `<unknown>` e nunca escala:

```bash
kubectl top pods -n homolog    # se isto responder, o HPA tem base de cálculo
```

### Reproduzindo a demonstração

Dois terminais. No primeiro, o observador:

```bash
kubectl get hpa,pods -n homolog -w
```

No segundo, a carga — uma rajada de logins, o endpoint mais caro em CPU da API:

```bash
export APP_SEED_ADMIN_PASSWORD=...       # a mesma senha entregue no Secret
NAMESPACE=homolog infra/scripts/load-test.sh        # 40 conexões por 5 minutos
```

Duração e concorrência saem de `LOAD_DURATION_SECONDS` e `LOAD_CONCURRENCY`. Na medição de
referência, `LOAD_CONCURRENCY=30` já levou o pod a 138% do seu request de CPU, e o autoscaler criou
réplicas em menos de um minuto:

```
SuccessfulRescale   New size: 3; reason: cpu resource utilization (percentage of request) above target
SuccessfulRescale   New size: 2; reason: All metrics below target
```

O segundo evento é o do fim da carga: passado o minuto de calmaria, as réplicas caem uma a uma até
voltar a uma. O ciclo inteiro leva cerca de dez minutos.

Aplique os manifestos **antes** de começar a carga, nunca durante. Um `kubectl apply -k` no meio da
rajada devolve o `Deployment` a uma réplica por um instante — é o efeito de remover o campo
`replicas`, que o apply poda do objeto vivo — e a demonstração dá um solavanco que não é do
autoscaler.

Se o HPA insistir em `<unknown>` **sob carga**, olhe as probes antes do autoscaler. Um pod no teto da
sua cota de CPU demora a responder, e o HPA ignora pod não-pronto: com o timeout default de um
segundo, o único pod saía da conta justamente quando havia carga para medir. É por isso que as três
probes do `Deployment` declaram `timeoutSeconds: 5`.

## Quando o lab é reiniciado

Reiniciar a sessão do Learner Lab cancela as credenciais; o que continua de pé depende do que o lab
preserva. Depois de reiniciar:

1. Renove as credenciais e regrave `HOMOLOG_AWS_*` da Organization para homolog e `AWS_*` do Environment de production.
2. Gere um novo kubeconfig com `aws eks update-kubeconfig --name oficina --region us-east-1`.
3. Confirme com `kubectl get nodes` e com o smoke test antes de concluir que algo quebrou.

Enquanto o banco não está alcançável, a aplicação reinicia em laço com
`Acquisition timeout while waiting for new connection` no Flyway — é esperado e se resolve sozinho
quando o banco volta.
