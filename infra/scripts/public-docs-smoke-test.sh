#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?Uso: public-docs-smoke-test.sh <gateway-url>}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-30}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
BASE_URL="${BASE_URL%/}"

fail() {
    echo "FALHA: $*" >&2
    exit 1
}

[[ "$BASE_URL" =~ ^https?://[^/]+/?$ ]] || fail "URL do Gateway inválida."
command -v curl >/dev/null || fail "curl não está instalado."
command -v jq >/dev/null || fail "jq não está instalado."

status="$(curl --silent --show-error --location --max-redirs 5 --max-time "$REQUEST_TIMEOUT_SECONDS" \
    --output "$TEMP_DIR/openapi.json" --write-out '%{http_code}' "$BASE_URL/q/openapi?format=json")" \
    || fail "GET /q/openapi não recebeu resposta do Gateway."
[[ "$status" == "200" ]] || fail "GET /q/openapi?format=json devolveu HTTP $status, esperado 200."
jq -e '(.openapi | strings) or (.swagger | strings)' "$TEMP_DIR/openapi.json" >/dev/null \
    || fail "GET /q/openapi não devolveu um documento OpenAPI válido."

status="$(curl --silent --show-error --location --max-redirs 5 --max-time "$REQUEST_TIMEOUT_SECONDS" \
    --output "$TEMP_DIR/swagger.html" --write-out '%{http_code}' "$BASE_URL/q/swagger-ui")" \
    || fail "GET /q/swagger-ui não recebeu resposta do Gateway."
[[ "$status" == "200" ]] || fail "GET /q/swagger-ui devolveu HTTP $status, esperado 200."
grep -Eiq '<!doctype html|<html' "$TEMP_DIR/swagger.html" \
    || fail "GET /q/swagger-ui não devolveu a página HTML do Swagger UI."

echo "OpenAPI e Swagger UI públicos e sem credenciais."
