# Pipeline dividida entre provisionamento manual e entrega automática

> **Status: substituído pelo [ADR-0007](0007-quatro-repositorios-com-deploy-por-branch-e-um-cluster-para-dois-ambientes.md).**
> O provisionamento deixou de ser um workflow manual deste repositório: o Terraform vive em
> `oficina-infra-k8s` e `oficina-infra-db`, e roda por branch. O texto abaixo é mantido como registro
> do raciocínio da época.

A automação de CI/CD é dividida em dois workflows: um de provisionamento, disparado manualmente
(`workflow_dispatch`), que roda o Terraform do EKS e do RDS; e um de entrega, que constrói a imagem,
publica no ECR, aplica os manifestos de `/k8s` e executa as migrations. O "deploy do banco de dados"
exigido pelo requisito é atendido pelo provisionamento do RDS no primeiro workflow e pelas
migrations do Flyway no segundo.

## Considered Options

A leitura literal do requisito colocaria `terraform apply` no mesmo workflow do deploy, a cada push.
Rejeitada porque o control plane do EKS leva 15 a 20 minutos para convergir, porque as credenciais
do Learner Lab expiram no meio de execuções longas, e porque compartilhar state do Terraform entre
execuções concorrentes de CI é a origem clássica de lock preso.

## Consequences

O state do Terraform fica no S3 com chave fixa, e o provisionamento é uma ação deliberada de um
operador, não um efeito colateral de merge. Em contrapartida, o ambiente pode divergir do HCL sem
que nenhuma pipeline acuse — não há `terraform plan` contínuo em pull request. O que o pull request
alcança sem credencial é menos que isso e ainda assim vale: formatação, sintaxe e tipos do HCL com
`-backend=false`, mais o schema dos manifestos de `/k8s`. Pega erro de escrita em segundos; não pega
ambiente divergente do código.
