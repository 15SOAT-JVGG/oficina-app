#!/usr/bin/env bash
# Cria no cluster os dois Secrets que o Deployment consome. Eles não estão em k8s/ de
# propósito: senha do banco, credenciais de seed e chave privada RS256 não entram no
# repositório.
#
#   oficina-mecanica-env  variáveis sensíveis, injetadas por envFrom
#   oficina-mecanica-jwt  o par RS256, montado como arquivo em /etc/jwt
#
# Tudo chega por variável de ambiente, o que serve igualmente ao operador e à pipeline —
# que não tem credencial da AWS (ADR-0002) e recebe os valores dos secrets do GitHub
# Environment:
#
#   usuário e senha do banco  POSTGRES_USERNAME / POSTGRES_PASSWORD, os mesmos do
#                             oficina-infra-db (`db_username` e o secret DB_PASSWORD)
#   par RS256                 JWT_PRIVATE_KEY_B64 / JWT_PUBLIC_KEY_B64 quando definidos,
#                             senão um diretório local gerado por
#                             infra/scripts/generate-jwt-pair.sh
#   credenciais de SMTP       MAIL_USERNAME / MAIL_PASSWORD, opcionais: sem elas o
#                             mailer só entrega se estiver em modo simulado
#
# NAMESPACE escolhe o ambiente. Sem ele vale o namespace do contexto atual do kubectl, que
# no kubeconfig da pipeline já é o do ambiente.
#
# Idempotente: reescreve os dois a cada execução. Reiniciar os pods depois é de quem
# chama (`kubectl rollout restart deploy/oficina-mecanica`).
#
# Uso: NAMESPACE=homolog infra/scripts/create-app-credentials.sh [diretório-do-par-rs256]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JWT_DIR="${1:-$REPO_ROOT/.local-jwt}"

ENV_SECRET="oficina-mecanica-env"
JWT_SECRET="oficina-mecanica-jwt"
PRIVATE_PEM="privateKey.pem"
PUBLIC_PEM="publicKey.pem"

command -v kubectl >/dev/null || { echo "kubectl não encontrado" >&2; exit 1; }

# Todo kubectl daqui passa pelo namespace pedido, quando houver um.
kube() {
    kubectl ${NAMESPACE:+--namespace "$NAMESPACE"} "$@"
}

for required in POSTGRES_USERNAME POSTGRES_PASSWORD APP_SEED_ADMIN_PASSWORD APP_SEED_MECHANIC_PASSWORD; do
    if [[ -z "${!required:-}" ]]; then
        echo "$required não definida." >&2
        echo "Sem as credenciais do banco o pod não conecta, e sem as de seed a aplicação" >&2
        echo "sobe sem usuário para autenticar. Exporte as quatro:" >&2
        echo "  export POSTGRES_USERNAME=... POSTGRES_PASSWORD=..." >&2
        echo "  export APP_SEED_ADMIN_PASSWORD=... APP_SEED_MECHANIC_PASSWORD=..." >&2
        exit 1
    fi
done

# Usuário e senha do SMTP são segredo e por isso vivem aqui, enquanto host, porta e
# remetente ficam no ConfigMap: a conta de e-mail tem ciclo de vida próprio, e trocá-la
# não deveria pedir um commit. As duas andam juntas — o servidor recusa a autenticação
# com apenas uma delas, e o sintoma seria um e-mail que nunca chega, sem erro no deploy.
MAIL_USERNAME="${MAIL_USERNAME:-}"
MAIL_PASSWORD="${MAIL_PASSWORD:-}"
if [[ -n "$MAIL_USERNAME" && -z "$MAIL_PASSWORD" ]] || [[ -z "$MAIL_USERNAME" && -n "$MAIL_PASSWORD" ]]; then
    echo "MAIL_USERNAME e MAIL_PASSWORD andam juntas: defina as duas, ou nenhuma." >&2
    exit 1
fi
if [[ -z "$MAIL_USERNAME" ]]; then
    echo "Sem credenciais de SMTP: o e-mail só sai se MAIL_MOCK for true no ConfigMap."
fi

# Em CI o par chega por variável de ambiente; localmente, por arquivo.
JWT_WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$JWT_WORK_DIR"' EXIT

# Decodifica um secret em base64 para arquivo, dizendo qual deles está inválido — o erro
# cru do `base64` ("invalid input") não diz se o problema é a chave privada ou a pública.
# Espaços e quebras de linha são descartados: colar o valor no GitHub costuma trazer uma.
decode_pem_secret() {
    local variable_name="$1" output_file="$2"
    if ! tr -d '[:space:]' <<<"${!variable_name}" | base64 --decode >"$output_file" 2>/dev/null || [[ ! -s "$output_file" ]]; then
        echo "$variable_name não é um base64 válido." >&2
        echo "Regrave o secret com a saída de infra/scripts/generate-jwt-pair.sh." >&2
        exit 1
    fi
}

if [[ -n "${JWT_PRIVATE_KEY_B64:-}" && -n "${JWT_PUBLIC_KEY_B64:-}" ]]; then
    decode_pem_secret JWT_PRIVATE_KEY_B64 "$JWT_WORK_DIR/$PRIVATE_PEM"
    decode_pem_secret JWT_PUBLIC_KEY_B64 "$JWT_WORK_DIR/$PUBLIC_PEM"
    echo "Par RS256 lido das variáveis de ambiente."
elif [[ -s "$JWT_DIR/$PRIVATE_PEM" && -s "$JWT_DIR/$PUBLIC_PEM" ]]; then
    cp "$JWT_DIR/$PRIVATE_PEM" "$JWT_DIR/$PUBLIC_PEM" "$JWT_WORK_DIR/"
    echo "Par RS256 lido de $JWT_DIR."
else
    echo "Par RS256 não encontrado." >&2
    echo "Defina JWT_PRIVATE_KEY_B64 e JWT_PUBLIC_KEY_B64, ou gere um par local:" >&2
    echo "  infra/scripts/generate-jwt-pair.sh" >&2
    exit 1
fi

# `kubectl create` sozinho falha quando o objeto já existe; passar por --dry-run=client
# e aplicar é o que torna a reexecução possível.
kube create secret generic "$ENV_SECRET" \
    --from-literal=POSTGRES_USERNAME="$POSTGRES_USERNAME" \
    --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    --from-literal=APP_SEED_ADMIN_PASSWORD="$APP_SEED_ADMIN_PASSWORD" \
    --from-literal=APP_SEED_MECHANIC_PASSWORD="$APP_SEED_MECHANIC_PASSWORD" \
    --from-literal=MAIL_USERNAME="$MAIL_USERNAME" \
    --from-literal=MAIL_PASSWORD="$MAIL_PASSWORD" \
    --dry-run=client -o yaml | kube apply -f -

kube create secret generic "$JWT_SECRET" \
    --from-file="$PRIVATE_PEM=$JWT_WORK_DIR/$PRIVATE_PEM" \
    --from-file="$PUBLIC_PEM=$JWT_WORK_DIR/$PUBLIC_PEM" \
    --dry-run=client -o yaml | kube apply -f -

echo "$ENV_SECRET e $JWT_SECRET aplicados${NAMESPACE:+ em $NAMESPACE}."
