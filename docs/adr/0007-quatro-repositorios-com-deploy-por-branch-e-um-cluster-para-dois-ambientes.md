# Quatro repositórios com deploy por branch e um cluster para dois ambientes

O sistema é dividido em quatro repositórios, cada um com a própria pipeline: `oficina-app` (API e
manifestos), `oficina-infra-k8s` (EKS, ECR e `metrics-server`), `oficina-infra-db` (RDS) e
`oficina-lambda-auth` (autenticação por CPF). Em todos, `main` é a branch de integração e as
branches `homolog` e `production` implantam o ambiente de mesmo nome: o push nelas dispara o
`terraform apply` (nos repositórios de infraestrutura) ou o deploy da imagem (no `oficina-app`). As
três são protegidas por ruleset — só entram por pull request, sem force-push nem exclusão — e a
promoção é um merge `main` → `homolog` → `production`.

Os dois ambientes dividem **um cluster EKS**. Eles se isolam por namespace (`homolog` e
`production`) e por uma instância de RDS própria cada; cada namespace tem a sua `ServiceAccount` de
deploy, com permissão só nele ([ADR-0002](0002-deploy-no-cluster-via-kubeconfig-de-serviceaccount.md)).
Os manifestos são uma base em `k8s/base` com um overlay Kustomize por ambiente.

## Considered Options

**Um repositório só (monorepo).** Foi o ponto de partida: Terraform, aplicação e Lambda no mesmo
lugar. Rejeitado porque o enunciado exige repositórios separados, e porque ciclos de vida diferentes
passam a se bloquear: um ajuste de manifesto não deveria rodar `terraform plan` do cluster, e uma
mudança no cluster não deveria passar pela suíte de testes da API.

**Um cluster por ambiente.** Isolamento mais forte, mas o control plane do EKS cobra por hora e o
Learner Lab tem crédito limitado. Namespaces, RBAC por namespace e banco separado dão o isolamento
que o projeto precisa; um cluster para homologação e outro para produção seria o passo seguinte numa
conta que não fosse de avaliação.

**`apply` manual, como no ADR-0003.** Fazia sentido quando o Terraform dividia o repositório da
aplicação e o `apply` do EKS ainda era um evento raro. Com repositórios próprios, o `apply`
automático por branch é o que dá rastreabilidade — cada mudança de infraestrutura é um pull request
com `plan` visível — e a execução concorrente é contida por `concurrency` no workflow, contra o lock
preso que o ADR-0003 temia.

## Consequences

`production` só é provisionado perto da entrega, para não gastar crédito: o overlay carrega um
`PREENCHER` no host do banco até o RDS existir, e o workflow de deploy recusa o valor.

Os dois ambientes compartilham o cluster, então um problema no control plane ou nos nós afeta ambos.
É uma troca consciente de isolamento por custo.

Na conta atual do lab, o cluster e o grupo de nós usam a `LabRole` — ver o que mudou em relação ao
[ADR-0001](0001-eks-provisionado-com-roles-do-lab-na-vpc-default.md), que previa as roles dedicadas
de EKS. A VPC default segue sendo reaproveitada, com as subnets públicas, sem NAT Gateway.

As credenciais do lab são secrets da organização e precisam ser regravadas a cada sessão (cerca de
quatro horas). Um deploy vermelho logo após o lab reiniciar quase sempre é isso.
