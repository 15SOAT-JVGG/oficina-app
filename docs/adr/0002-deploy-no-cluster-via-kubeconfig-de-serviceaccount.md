# Deploy no cluster via kubeconfig de ServiceAccount, não via OIDC

O GitHub Actions autentica no cluster com um kubeconfig apontando para o endpoint público do EKS e
um token de `ServiceAccount` dedicada, guardado como um único secret do repositório. OIDC com role
assumida — o padrão atual para CI na AWS — exige criar uma role IAM, o que o AWS Academy
(Learner Lab) não permite.

## Considered Options

A alternativa era colocar `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` e `AWS_SESSION_TOKEN` como
secrets e rodar `aws eks update-kubeconfig`. Funciona, mas as credenciais do Learner Lab expiram
com a sessão (~4h), o que deixaria o job de deploy vermelho por motivo alheio ao código e exigiria
recolar três secrets antes de cada demonstração. O token da `ServiceAccount` não depende do ciclo
de vida da sessão do lab.

## Consequences

O job de deploy não usa credencial AWS alguma. Os jobs que precisam da AWS de fato usam as
credenciais temporárias do lab e por isso ficam isolados dele: o `terraform apply` roda nas pipelines dos
repositórios `oficina-infra-*`, e o push da imagem no ECR é um job separado no caminho de entrega, que confere a
sessão com `aws sts get-caller-identity` antes de qualquer outra coisa — sessão vencida falha em
segundos e o cluster não é tocado. O token da `ServiceAccount` é de longa duração e concede permissão
no cluster: se vazar, não expira sozinho, e a mitigação é apagar a `ServiceAccount`.
