#!/usr/bin/env bash
# Cria no cluster a identidade que a pipeline de entrega usa para aplicar os manifestos,
# e grava o kubeconfig correspondente num arquivo fora do repositório.
#
# A pipeline não autentica com credencial da AWS: a sessão do Learner Lab expira em umas
# quatro horas, e o job de deploy ficaria vermelho por motivo alheio ao código
# (ADR-0002). Ela autentica com o token de uma ServiceAccount dedicada, guardado como um
# único secret do repositório.
#
# O que é criado no namespace do ambiente (homolog ou production):
#
#   Namespace      o do ambiente, se ainda não existir (a ServiceAccount não tem
#                  permissão para criá-lo, por isso é este script, com perfil de
#                  administrador, que o cria)
#   ServiceAccount github-deployer  a identidade
#   Secret         github-deployer-token  token de longa duração, preenchido pelo cluster
#   Role/RoleBinding github-deployer  só o que o deploy faz: aplicar os objetos da
#                                     aplicação, acompanhar o rollout e ler o endereço
#                                     do Service
#
# Rode uma vez por ambiente, com um kubeconfig de administrador (o de
# `aws eks update-kubeconfig`). Cada ambiente tem a sua ServiceAccount, e a permissão dela
# vale só dentro do próprio namespace: o token de homologação não alcança produção. É
# idempotente: reexecutar reaplica os objetos e regrava o arquivo com o mesmo token.
#
# O token não expira sozinho. Se vazar, a mitigação é apagar a ServiceAccount:
#   kubectl delete serviceaccount github-deployer -n <namespace>
#
# Uso: infra/scripts/create-deploy-kubeconfig.sh <homolog|production> [arquivo-de-saída]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

NAMESPACE="${1:-}"
if [[ "$NAMESPACE" != "homolog" && "$NAMESPACE" != "production" ]]; then
    echo "Informe o ambiente: homolog ou production." >&2
    echo "Uso: $0 <homolog|production> [arquivo-de-saída]" >&2
    exit 1
fi
OUTPUT_FILE="${2:-$REPO_ROOT/.local-kube/deploy-kubeconfig-$NAMESPACE.yaml}"

SERVICE_ACCOUNT="github-deployer"
TOKEN_SECRET="$SERVICE_ACCOUNT-token"
TOKEN_TIMEOUT_SECONDS=30
TOKEN_RETRY_SECONDS=2

command -v kubectl >/dev/null || { echo "kubectl não encontrado" >&2; exit 1; }

# O contexto atual é a fonte do endereço e da CA do cluster — evita colar à mão o
# endpoint do EKS, que muda a cada recriação do control plane.
CLUSTER_SERVER="$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
CLUSTER_CA="$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"
if [[ -z "$CLUSTER_SERVER" || -z "$CLUSTER_CA" ]]; then
    echo "O kubeconfig atual não descreve um cluster com endereço e CA." >&2
    echo "Grave o acesso ao cluster antes de rodar:" >&2
    echo "  aws eks update-kubeconfig --name oficina --region us-east-1" >&2
    exit 1
fi

# O Secret do tipo service-account-token é o que dá um token de longa duração: `kubectl
# create token` emite um token com prazo, e um secret do repositório que vence sozinho
# deixaria a entrega parada até alguém regravá-lo.
kubectl apply -f - <<YAML
apiVersion: v1
kind: Namespace
metadata:
  name: $NAMESPACE
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: $SERVICE_ACCOUNT
  namespace: $NAMESPACE
---
apiVersion: v1
kind: Secret
metadata:
  name: $TOKEN_SECRET
  namespace: $NAMESPACE
  annotations:
    kubernetes.io/service-account.name: $SERVICE_ACCOUNT
type: kubernetes.io/service-account-token
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: $SERVICE_ACCOUNT
  namespace: $NAMESPACE
rules:
  # Os objetos que o deploy aplica. Sem delete: a pipeline atualiza o que existe, e
  # apagar recurso da aplicação é decisão de operador.
  - apiGroups: [""]
    resources: [configmaps, secrets, services]
    verbs: [get, list, watch, create, update, patch]
  - apiGroups: [apps]
    resources: [deployments]
    verbs: [get, list, watch, create, update, patch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch, create, update, patch]
  # Só leitura, e só para diagnosticar rollout que não converge.
  - apiGroups: [apps]
    resources: [replicasets]
    verbs: [get, list, watch]
  - apiGroups: [""]
    resources: [pods, pods/log, events]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: $SERVICE_ACCOUNT
  namespace: $NAMESPACE
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: $SERVICE_ACCOUNT
subjects:
  - kind: ServiceAccount
    name: $SERVICE_ACCOUNT
    namespace: $NAMESPACE
YAML

# O token é preenchido pelo controlador de tokens, não pelo apply: ler o Secret na
# sequência devolve vazio.
echo "Aguardando o cluster preencher o token de $SERVICE_ACCOUNT..."
DEADLINE=$((SECONDS + TOKEN_TIMEOUT_SECONDS))
while :; do
    TOKEN="$(kubectl get secret "$TOKEN_SECRET" -n "$NAMESPACE" -o jsonpath='{.data.token}' 2>/dev/null || true)"
    [[ -n "$TOKEN" ]] && break
    ((SECONDS < DEADLINE)) || {
        echo "O token não foi preenchido em ${TOKEN_TIMEOUT_SECONDS}s." >&2
        echo "Investigue o Secret: kubectl describe secret $TOKEN_SECRET -n $NAMESPACE" >&2
        exit 1
    }
    sleep "$TOKEN_RETRY_SECONDS"
done
TOKEN="$(base64 --decode <<<"$TOKEN")"

mkdir -p "$(dirname "$OUTPUT_FILE")"
umask 077
cat >"$OUTPUT_FILE" <<YAML
apiVersion: v1
kind: Config
current-context: $SERVICE_ACCOUNT
clusters:
  - name: cluster
    cluster:
      server: $CLUSTER_SERVER
      certificate-authority-data: $CLUSTER_CA
users:
  - name: $SERVICE_ACCOUNT
    user:
      token: $TOKEN
contexts:
  - name: $SERVICE_ACCOUNT
    context:
      cluster: cluster
      namespace: $NAMESPACE
      user: $SERVICE_ACCOUNT
YAML
chmod 600 "$OUTPUT_FILE"

echo "Kubeconfig de $SERVICE_ACCOUNT gravado em $OUTPUT_FILE."
echo
echo "Confira o acesso:"
echo "  KUBECONFIG=$OUTPUT_FILE kubectl get deploy"
echo
echo "Guarde no secret do GitHub Environment '$NAMESPACE' do repositório oficina-app:"
echo "  gh secret set KUBE_CONFIG_B64 --env $NAMESPACE --body \"\$(base64 < $OUTPUT_FILE | tr -d '\\n')\""
