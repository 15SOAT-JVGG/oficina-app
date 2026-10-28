# API Gateway e Lambda Authorizer

O fluxo público usa duas Lambdas: `POST /v1/auth/customer` emite JWT RS256; o Authorizer `REQUEST` valida assinatura, issuer, expiração, subject e grupo antes de encaminhar rotas privadas ao Quarkus. Logins, health checks, links assinados e documentação habilitada permanecem públicos apenas nas rotas enumeradas em `oficina-infra-k8s/api-gateway.tf`.

Em `homolog`, a pipeline aplica RDS, EKS/Gateway, Lambdas e aplicação nessa ordem. O deploy da aplicação publica o ELB como contrato versionado no bucket de configuração; a Lambda publica o ARN versionado do Authorizer. O workflow reutilizável de `oficina-infra-k8s` reconcilia as rotas no state compartilhado e rejeita planos que alterem o cluster, o ECR ou o outro ambiente. O smoke test final chama a URL do API Gateway.

O Environment `homolog` da Lambda precisa de `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `DB_PASSWORD` e `JWT_PRIVATE_KEY_B64`. Se `JWT_PUBLIC_KEY_B64` não estiver cadastrado na Lambda, o workflow deriva a chave pública da chave privada antes do Terraform. A aplicação mantém o par JWT existente em seus próprios secrets. Configure `AWS_REGION` e `TF_STATE_BUCKET` como variables nos quatro repositórios.

O acesso Kubernetes da aplicação usa `KUBE_CONFIG_B64`, restrito à ServiceAccount do namespace. Nunca versione kubeconfig, chaves JWT, senha do banco, senha de seed ou state Terraform.
