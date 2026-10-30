#!/usr/bin/env bash
# Responde, num único comando, se o deploy funcionou. Exercita de fora para dentro o
# balanceador, o pod, o ConfigMap, os Secret, a conectividade com o banco e as
# migrations: se este script passa, o ambiente está de pé.
#
# O que ele verifica, e nada além disso:
#   1. o login público funciona depois que o pod ficou Ready no cluster
#   2. uma ordem de serviço é criada e aparece na listagem em seguida
#
# Regra de negócio não se verifica aqui — WorkOrderControllerIT e SecurityIT já cobrem
# ciclo de vida da OS, orçamento, estoque e RBAC contra um Postgres real. Uma falha
# deste script aponta para infraestrutura, não para domínio.
#
# Cliente e veículo são criados porque a abertura da OS exige os dois; são preparação,
# não afirmação. Os dados ficam no banco depois da execução, de propósito: eles são o
# que a demonstração usa em seguida.
#
# Uso:
#   NAMESPACE=homolog infra/scripts/smoke-test.sh     # descobre o endereço do Service no cluster
#   infra/scripts/smoke-test.sh http://localhost:8080 # ou um endereço explícito
#
# NAMESPACE escolhe o ambiente; sem ele vale o namespace do contexto atual do kubectl.
#
# Requer curl e jq, mais kubectl quando o endereço não é informado. E requer
# APP_SEED_ADMIN_PASSWORD — a mesma senha entregue ao cluster por
# infra/scripts/create-app-credentials.sh. APP_SEED_ADMIN_USERNAME é opcional
# (default: admin), e READY_TIMEOUT_SECONDS ajusta a espera pelo readiness.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/environment.sh"

READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-180}"
READY_RETRY_SECONDS=5
REQUEST_TIMEOUT_SECONDS=30
WORK_ORDERS_PAGE_SIZE=100

require_tools curl jq
require_seed_admin_password
resolve_base_url "${1:-}"

RESPONSE_BODY_FILE="$(mktemp)"
trap 'rm -f "$RESPONSE_BODY_FILE"' EXIT

TOKEN=""
RESPONSE_BODY=""
WORK_ORDER_ID=""

fail() {
    echo "FALHA: $*" >&2
    exit 1
}

# Todo request passa por aqui para que a afirmação seja sempre a mesma: o código HTTP
# esperado. O corpo fica em RESPONSE_BODY para quem precisa de um identificador.
request() {
    local method="$1" path="$2" expected_status="$3" payload="${4:-}"
    local curl_args=(
        --silent --show-error
        --max-time "$REQUEST_TIMEOUT_SECONDS"
        --request "$method"
        --header 'Accept: application/json'
        --output "$RESPONSE_BODY_FILE"
        --write-out '%{http_code}'
    )
    [[ -n "$payload" ]] && curl_args+=(--header 'Content-Type: application/json' --data "$payload")
    [[ -n "$TOKEN" ]] && curl_args+=(--header "Authorization: Bearer $TOKEN")

    local status
    status="$(curl "${curl_args[@]}" "$BASE_URL$path")" \
        || fail "$method $path não recebeu resposta de $BASE_URL"

    RESPONSE_BODY="$(cat "$RESPONSE_BODY_FILE")"
    [[ "$status" == "$expected_status" ]] \
        || fail "$method $path devolveu $status, esperado $expected_status: $RESPONSE_BODY"
}

extract() {
    jq -er "$1" <<<"$RESPONSE_BODY" || fail "resposta sem $1: $RESPONSE_BODY"
}

# O ELB começa a responder cerca de um minuto depois de nascer, e o pod tem migrations
# para rodar antes de ficar pronto. Esperar aqui é o que separa "ambiente quebrado" de
# "ambiente ainda subindo".
wait_until_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT_SECONDS)) status payload
    payload="$(login_payload)"
    while :; do
        status="$(curl --silent --max-time "$REQUEST_TIMEOUT_SECONDS" \
            --request POST \
            --header 'Accept: application/json' \
            --header 'Content-Type: application/json' \
            --output "$RESPONSE_BODY_FILE" --write-out '%{http_code}' \
            --data "$payload" \
            "$BASE_URL/v1/auth/login")" || status="sem resposta"
        if [[ "$status" == "200" ]]; then
            RESPONSE_BODY="$(cat "$RESPONSE_BODY_FILE")"
            TOKEN="$(extract '.token')"
            return
        fi
        [[ "$status" != "401" ]] || fail "login de seed recusado; confira usuário e senha configurados."
        ((SECONDS < deadline)) \
            || fail "o login não ficou disponível em ${READY_TIMEOUT_SECONDS}s (último: $status)"
        sleep "$READY_RETRY_SECONDS"
    done
}

# Documento e placa novos a cada execução: rodar o smoke test duas vezes seguidas não
# pode falhar por unicidade. Os dígitos verificadores do CPF são calculados aqui porque
# a aplicação recusa documento inválido na entrada — é o algoritmo público do CPF, não
# uma regra desta oficina.
generate_document() {
    local digits=() index check_digit sum first_weight remainder
    for ((index = 0; index < 9; index++)); do
        digits+=($((RANDOM % 10)))
    done
    for ((check_digit = 0; check_digit < 2; check_digit++)); do
        sum=0
        first_weight=$((10 + check_digit))
        for ((index = 0; index < 9 + check_digit; index++)); do
            sum=$((sum + digits[index] * (first_weight - index)))
        done
        remainder=$((sum % 11))
        digits+=($((remainder < 2 ? 0 : 11 - remainder)))
    done
    printf '%s' "${digits[@]}"
}

random_letter() {
    local alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    printf '%s' "${alphabet:RANDOM % ${#alphabet}:1}"
}

generate_license_plate() {
    local plate="" index
    for ((index = 0; index < 3; index++)); do
        plate+="$(random_letter)"
    done
    printf '%s%d%s%02d' "$plate" $((RANDOM % 10)) "$(random_letter)" $((RANDOM % 100))
}

# A abertura já devolve o identificador no corpo do 201; percorrer a listagem é o que
# confirma que a OS chegou à fila operacional. O veículo é novo nesta execução e por isso
# tem uma OS só, mas ela pode cair em qualquer página: a fila é ordenada por estágio de
# trabalho e o banco do ambiente acumula OS de execuções anteriores. Percorrer as páginas
# é o que evita uma falha que não é do ambiente.
find_created_work_order() {
    local page=0 total_pages
    while :; do
        request GET "/v1/work-orders?page=$page&size=$WORK_ORDERS_PAGE_SIZE" 200
        WORK_ORDER_ID="$(jq -r --arg vehicleId "$VEHICLE_ID" \
            'first(.content[] | select(.vehicleId == $vehicleId) | .workOrderId) // empty' \
            <<<"$RESPONSE_BODY")"
        if [[ -n "$WORK_ORDER_ID" ]]; then
            return
        fi
        total_pages="$(extract '.pagination.totalPages')"
        page=$((page + 1))
        ((page < total_pages)) \
            || fail "a OS criada para o veículo $VEHICLE_ID não apareceu nas $total_pages páginas da listagem"
    done
}

echo "Smoke test contra $BASE_URL"

status="$(curl --silent --show-error --location --max-redirs 5 --max-time "$REQUEST_TIMEOUT_SECONDS" \
    --output "$RESPONSE_BODY_FILE" --write-out '%{http_code}' "$BASE_URL/q/openapi")" \
    || fail "GET /q/openapi não recebeu resposta de $BASE_URL"
[[ "$status" == "200" ]] \
    || fail "GET /q/openapi devolveu $status, esperado 200"
jq -e '(.openapi | strings) or (.swagger | strings)' "$RESPONSE_BODY_FILE" >/dev/null \
    || fail "GET /q/openapi não devolveu um documento OpenAPI válido"

status="$(curl --silent --show-error --location --max-redirs 5 --max-time "$REQUEST_TIMEOUT_SECONDS" \
    --output "$RESPONSE_BODY_FILE" --write-out '%{http_code}' "$BASE_URL/q/swagger-ui")" \
    || fail "GET /q/swagger-ui não recebeu resposta de $BASE_URL"
[[ "$status" == "200" ]] \
    || fail "GET /q/swagger-ui devolveu $status, esperado 200"
grep -Eiq '<!doctype html|<html' "$RESPONSE_BODY_FILE" \
    || fail "GET /q/swagger-ui não devolveu a página HTML do Swagger UI"
echo "OpenAPI e Swagger UI públicos e sem autenticação"

wait_until_ready
echo "1/4 API e banco prontos"
echo "2/4 autenticado como $ADMIN_USERNAME"

DOCUMENT="$(generate_document)"
LICENSE_PLATE="$(generate_license_plate)"

request POST /v1/customer 201 "$(jq -nc --arg document "$DOCUMENT" '{
    firstName: "Cliente",
    lastName: "Smoke Test",
    email: "smoke.\($document)@example.com",
    phoneNumber: "+5511999887766",
    document: $document
}')"
request GET "/v1/customer/by-document/$DOCUMENT" 200
CUSTOMER_ID="$(extract '.customerId')"

request POST /v1/vehicle 201 "$(jq -nc \
    --arg customerId "$CUSTOMER_ID" \
    --arg licensePlate "$LICENSE_PLATE" \
    '{
        customerId: $customerId,
        licensePlate: $licensePlate,
        manufacturer: "Fiat",
        model: "Argo",
        color: "Prata",
        year: 2022,
        kmDriven: 45000,
        type: "CAR"
    }')"
request GET "/v1/vehicle/by-license-plate/$LICENSE_PLATE" 200
VEHICLE_ID="$(extract '.id')"
echo "3/4 cliente $CUSTOMER_ID e veículo $LICENSE_PLATE preparados"

request POST /v1/work-orders 201 "$(jq -nc \
    --arg customerId "$CUSTOMER_ID" \
    --arg vehicleId "$VEHICLE_ID" \
    '{
        customerId: $customerId,
        vehicleId: $vehicleId,
        description: "Smoke test do ambiente implantado",
        priority: "MEDIUM"
    }')"

find_created_work_order
echo "4/4 ordem de serviço $WORK_ORDER_ID criada e recuperada"

echo
echo "OK — o ambiente em $BASE_URL está operacional"
