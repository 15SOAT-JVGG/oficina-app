#!/usr/bin/env bash
# Prova, de fora para dentro e pelo API Gateway, que a autenticação do cliente funciona de ponta a
# ponta: a rota do gateway, a Lambda, o banco, a chave JWT compartilhada e a validação do token na API.
#
# O que ele verifica, e nada além disso:
#   1. o gateway encaminha a API (/q/health/ready)
#   2. um cliente novo, cadastrado pelo admin, se autentica pelo CPF e recebe um JWT de CUSTOMER
#   3. esse JWT é aceito pela API em /v1/me/work-orders (prova que a chave e o issuer batem)
#   4. a API não deixa o cliente usar rota administrativa (RBAC)
#   5. CPF inválido, CPF sem cadastro e cliente desativado são recusados — os dois últimos com a
#      mesma resposta, de propósito: dizer qual dos dois ocorreu permitiria descobrir quem é cliente
#
# Cria um cliente novo a cada execução (CPF e e-mail inéditos) e o deixa desativado ao final.
#
# Uso:
#   export APP_SEED_ADMIN_PASSWORD=...
#   infra/scripts/e2e-auth.sh https://<id>.execute-api.us-east-1.amazonaws.com
#
# O endereço também pode vir de API_URL. Requer curl e jq. APP_SEED_ADMIN_USERNAME é opcional
# (default: admin).
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/environment.sh"

REQUEST_TIMEOUT_SECONDS=30
CUSTOMER_AUTH_PATH="/v1/auth/customer"
CUSTOMER_WORK_ORDERS_PATH="/v1/me/work-orders"

API_URL="${1:-${API_URL:-}}"
if [[ -z "$API_URL" ]]; then
    echo "Informe o endereço do API Gateway (argumento ou API_URL)." >&2
    echo "Uso: $0 https://<id>.execute-api.us-east-1.amazonaws.com" >&2
    exit 1
fi
API_URL="${API_URL%/}"

require_tools curl jq
require_seed_admin_password

RESPONSE_BODY_FILE="$(mktemp)"
trap 'rm -f "$RESPONSE_BODY_FILE"' EXIT
RESPONSE_BODY=""

fail() {
    echo "FALHA: $*" >&2
    exit 1
}

# Todo request passa por aqui para que a afirmação seja sempre a mesma: o código HTTP esperado.
# O token, quando houver, é o quinto argumento — o fluxo alterna entre admin e cliente.
call() {
    local method="$1" path="$2" expected_status="$3" payload="${4:-}" token="${5:-}"
    local curl_args=(
        --silent --show-error
        --max-time "$REQUEST_TIMEOUT_SECONDS"
        --request "$method"
        --header 'Accept: application/json'
        --output "$RESPONSE_BODY_FILE"
        --write-out '%{http_code}'
    )
    [[ -n "$payload" ]] && curl_args+=(--header 'Content-Type: application/json' --data "$payload")
    [[ -n "$token" ]] && curl_args+=(--header "Authorization: Bearer $token")

    local status
    status="$(curl "${curl_args[@]}" "$API_URL$path")" \
        || fail "$method $path não recebeu resposta de $API_URL"

    RESPONSE_BODY="$(cat "$RESPONSE_BODY_FILE")"
    [[ "$status" == "$expected_status" ]] \
        || fail "$method $path devolveu $status, esperado $expected_status: $RESPONSE_BODY"
}

extract() {
    jq -er "$1" <<<"$RESPONSE_BODY" || fail "resposta sem $1: $RESPONSE_BODY"
}

expect_equals() {
    local description="$1" actual="$2" expected="$3"
    [[ "$actual" == "$expected" ]] || fail "$description: esperado '$expected', recebido '$actual'"
}

authenticate_customer() {
    jq -nc --arg cpf "$1" '{cpf: $cpf}'
}

# O corpo de erro traz traceId e timestamp, que mudam a cada chamada; o que precisa ser igual entre
# as recusas é o que o cliente consegue observar da causa: status, código e mensagem.
refusal_signature() {
    jq -c '{status, code, message}' <<<"$RESPONSE_BODY"
}

echo "E2E de autenticação contra $API_URL"

call GET /q/health/ready 200
echo "1/8 gateway encaminha a API"

call POST /v1/auth/login 200 "$(login_payload)"
ADMIN_TOKEN="$(extract '.token')"
echo "2/8 admin autenticado"

DOCUMENT="$(generate_document)"
call POST /v1/customer 201 "$(jq -nc --arg document "$DOCUMENT" '{
    firstName: "Cliente",
    lastName: "E2E Auth",
    email: "e2e.\($document)@example.com",
    phoneNumber: "+5511999887766",
    document: $document
}')" "$ADMIN_TOKEN"
call GET "/v1/customer/by-document/$DOCUMENT" 200 "" "$ADMIN_TOKEN"
CUSTOMER_ID="$(extract '.customerId')"
echo "3/8 cliente cadastrado ($CUSTOMER_ID)"

call POST "$CUSTOMER_AUTH_PATH" 200 "$(authenticate_customer "$DOCUMENT")"
CUSTOMER_TOKEN="$(extract '.token')"
expect_equals "customerId do token" "$(extract '.customerId')" "$CUSTOMER_ID"
expect_equals "papel do token" "$(extract '.role')" "CUSTOMER"
echo "4/8 cliente autenticado pelo CPF (Lambda via gateway)"

call GET "$CUSTOMER_WORK_ORDERS_PATH" 401
call GET "$CUSTOMER_WORK_ORDERS_PATH" 200 "" "$CUSTOMER_TOKEN"
echo "5/8 JWT do cliente aceito pela API; sem token, 401"

call GET "/v1/customer/by-document/$DOCUMENT" 403 "" "$CUSTOMER_TOKEN"
echo "6/8 cliente barrado em rota administrativa"

call POST "$CUSTOMER_AUTH_PATH" 400 "$(authenticate_customer 11111111111)"
expect_equals "código do CPF inválido" "$(extract '.code')" "INVALID_CPF"
call POST "$CUSTOMER_AUTH_PATH" 401 "$(authenticate_customer "$(generate_document)")"
expect_equals "código do CPF sem cadastro" "$(extract '.code')" "CUSTOMER_NOT_AUTHORIZED"
UNKNOWN_CUSTOMER_REFUSAL="$(refusal_signature)"
echo "7/8 CPF inválido (400) e CPF sem cadastro (401) recusados"

# O controller consome JSON, então até um PATCH sem dados precisa de Content-Type; sem ele a resposta é 415.
call PATCH "/v1/customer/$CUSTOMER_ID/deactivate" 200 '{}' "$ADMIN_TOKEN"
call POST "$CUSTOMER_AUTH_PATH" 401 "$(authenticate_customer "$DOCUMENT")"
expect_equals "recusa do cliente inativo" "$(refusal_signature)" "$UNKNOWN_CUSTOMER_REFUSAL"
echo "8/8 cliente desativado recusado com a mesma resposta do CPF sem cadastro"

echo "OK: autenticação ponta a ponta funcionando."
