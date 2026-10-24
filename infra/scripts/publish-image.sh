#!/usr/bin/env bash
# Constrói a imagem da aplicação e publica no ECR criado pelo oficina-infra-k8s.
#
# A tag é o SHA do commit, para que o rollout no cluster seja sempre uma mudança real
# de imagem; `latest` acompanha por conveniência de inspeção manual.
#
# A plataforma é fixada em linux/amd64: os nós do EKS são t3.medium (x86_64) e uma
# imagem construída num Mac ARM sem essa flag sobe, mas nenhum pod inicia.
#
# Requer uma sessão ativa do lab e o repositório `oficina-app` já provisionado (pipeline do
# oficina-infra-k8s).
#
# Uso: infra/scripts/publish-image.sh [tag]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ECR_REPOSITORY_NAME="${ECR_REPOSITORY_NAME:-oficina-app}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

for tool in aws docker git; do
    command -v "$tool" >/dev/null || { echo "$tool não encontrado" >&2; exit 1; }
done

TAG="${1:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"

# O endereço do repositório carrega o ID da conta, que muda entre sessões do Learner Lab; por
# isso é perguntado à AWS em vez de versionado. Em CI ele já chega por variável de ambiente.
if [[ -z "${ECR_REPOSITORY_URL:-}" ]]; then
    ECR_REPOSITORY_URL="$(aws ecr describe-repositories \
        --region "$REGION" \
        --repository-names "$ECR_REPOSITORY_NAME" \
        --query 'repositories[0].repositoryUri' --output text)"
fi
REGISTRY="${ECR_REPOSITORY_URL%%/*}"

echo "Publicando $ECR_REPOSITORY_URL:$TAG"

aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY"

# Sem --provenance=false o buildx publica um índice OCI com manifestos filhos sem tag,
# que a política de ciclo de vida do ECR contaria como imagens e poderia expirar por
# baixo de uma tag ainda em uso.
docker buildx build \
    --platform linux/amd64 \
    --provenance=false \
    --file "$REPO_ROOT/infra/docker/Dockerfile" \
    --tag "$ECR_REPOSITORY_URL:$TAG" \
    --tag "$ECR_REPOSITORY_URL:latest" \
    --push \
    "$REPO_ROOT"

echo "Publicado: $ECR_REPOSITORY_URL:$TAG"
