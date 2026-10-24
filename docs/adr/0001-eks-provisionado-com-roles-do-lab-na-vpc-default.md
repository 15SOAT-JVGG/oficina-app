# EKS provisionado com recursos Terraform diretos, reusando as roles do lab e a VPC default

> **Status: parcialmente substituído pelo [ADR-0007](0007-quatro-repositorios-com-deploy-por-branch-e-um-cluster-para-dois-ambientes.md).**
> A decisão de declarar os recursos à mão, sem o módulo da comunidade, e de reaproveitar a VPC default
> continua valendo; o Terraform agora vive em `oficina-infra-k8s` e `oficina-infra-db`, e
> `infra/terraform` e `infra/scripts/verify-lab.sh` deixaram de existir neste repositório. A escolha
> das roles dedicadas de EKS descrita abaixo era da conta anterior: na conta atual o cluster e o grupo
> de nós usam a `LabRole`. O texto abaixo é mantido como registro do raciocínio da época.

O cluster Kubernetes e o RDS da Fase 2 rodam numa conta AWS Academy (Learner Lab), que não concede
`iam:CreateRole`. O Terraform em `infra/terraform` declara os recursos diretamente
(`aws_eks_cluster`, `aws_eks_node_group`, `aws_db_instance`) reusando roles pré-existentes e a VPC
default, em vez de usar o módulo `terraform-aws-modules/eks`.

## Considered Options

O módulo oficial da comunidade seria o caminho normal, mas cria roles IAM para o cluster e para o
node group por conta própria e falha no meio do `apply` nesta conta. Contorná-lo com
`create_iam_role = false` é possível, porém transforma qualquer erro em depuração de código de
terceiros — inaceitável no prazo desta fase. Escrever os recursos à mão também atende melhor o
requisito de documentar quais recursos são criados, já que a lista é exatamente o que está no HCL.

A decisão original era usar a `LabRole` nos dois papéis. A verificação da conta (#28) mostrou que o
lab também provisiona duas roles dedicadas a EKS, então o cluster usa a `*-LabEksClusterRole-*` e o
node group a `*-LabEksNodeRole-*`. O motivo decisivo é a CNI: entre as políticas gerenciadas, só a
role dedicada tem `AmazonEKS_CNI_Policy`, e sem permissão de CNI os nós sobem mas não chegam a
`Ready` — um `apply` que "funciona" e entrega um cluster inútil. Não é possível provar que a
`LabRole` careça dessa permissão, porque as políticas `VocLabPolicy*` anexadas a ela têm
`iam:GetPolicy` negado; usar a role dedicada é a escolha que não depende dessa incerteza.

## Consequences

O `/infra` não provisiona rede: se a VPC default for removida da conta, o `apply` quebra.

O nome das roles de EKS carrega um prefixo gerado pela stack do lab, que muda a cada
reprovisionamento, então elas são resolvidas por `data "aws_iam_roles"` com `name_regex` — ARN fixo
no HCL não sobrevive a um reset. Pela mesma razão, `infra/scripts/verify-lab.sh` precisa ser rodado
de novo depois de cada reset: os IDs de VPC e subnet também mudam.

O node group recebe 5 das 6 subnets da VPC default, porque `us-east-1e` não oferece `t3.medium` e
incluir aquela subnet faz a criação falhar. O filtro sai de
`data "aws_ec2_instance_type_offerings"`.

A classe do RDS é variável com default `db.t3.micro`: a conta libera `rds:CreateDBInstance` até
`db.t3.medium` e nega de `db.t3.large` para cima.

Não há separação de privilégio entre control plane e nós além do que essas roles já trazem, e nenhuma
delas é gerenciada por nós — aceitável para um ambiente de avaliação, não para produção.
