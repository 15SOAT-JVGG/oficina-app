# API Gateway e Lambda Authorizer

O fluxo público usa duas Lambdas: `POST /v1/auth/customer` emite JWT RS256; o Authorizer `REQUEST` valida assinatura, issuer, expiração, subject e grupo antes de encaminhar rotas privadas ao Quarkus. Em homolog, Swagger/OpenAPI são exceções públicas explícitas; as operações da API continuam com Authorizer. A integração entre o API Gateway e o cluster usa VPC Link para um NLB interno.

Em `homolog`, a pipeline aplica RDS, EKS/Gateway, Lambdas e aplicação nessa ordem. O deploy publica o listener NLB interno da aplicação como contrato versionado no bucket de configuração; a Lambda publica o ARN versionado do Authorizer. O workflow reutilizável de `oficina-infra-k8s` reconcilia as rotas no state compartilhado e rejeita planos que alterem o cluster, o ECR ou o outro ambiente. Depois do smoke test pela URL do Gateway, remove o Service público legado.

O Environment `homolog` da Lambda precisa de `DB_PASSWORD` e `JWT_PRIVATE_KEY_B64`. Os workflows de homolog usam os secrets Organization `HOMOLOG_AWS_ACCESS_KEY_ID`, `HOMOLOG_AWS_SECRET_ACCESS_KEY` e `HOMOLOG_AWS_SESSION_TOKEN`, restritos aos quatro repositórios. Se `JWT_PUBLIC_KEY_B64` não estiver cadastrado na Lambda, o workflow deriva a chave pública da chave privada antes do Terraform. A aplicação mantém o par JWT existente em seus próprios secrets. Configure `AWS_REGION` e `TF_STATE_BUCKET` como variables nos quatro repositórios.

Os workflows chamam uma reconciliação reutilizável que termina com uma matriz via Gateway real em homolog. Para os logins de cliente, configure `AUTHORIZER_TEST_CUSTOMER_CPF` com o CPF de um cliente sintético e ativo nos Environments `homolog` de `oficina-lambda-auth` e `oficina-app`. O summary registra só cenário, status HTTP e request ID.

O acesso Kubernetes da aplicação usa `KUBE_CONFIG_B64`, restrito à ServiceAccount do namespace. Nunca versione kubeconfig, chaves JWT, senha do banco, senha de seed ou state Terraform.
