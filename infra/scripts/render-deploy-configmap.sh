#!/usr/bin/env bash
# Renderiza somente os dados variáveis do ConfigMap usando outputs Terraform já resolvidos.
# Não recebe nem imprime credenciais.
set -euo pipefail

for required in INFRA_HOST_POSTGRES POSTGRES_PORT POSTGRES_DB APP_PUBLIC_BASE_URL JWT_ISSUER; do
    if [[ -z "${!required:-}" ]]; then
        echo "$required não foi resolvida para este ambiente." >&2
        exit 1
    fi
done

# jq @json produz strings compatíveis com YAML quoted scalar e evita interpolação acidental.
quote() { jq -Rn --arg value "$1" '$value'; }
cat <<MANIFEST
apiVersion: v1
kind: ConfigMap
metadata:
  name: oficina-mecanica-config
data:
  INFRA_HOST_POSTGRES: $(quote "$INFRA_HOST_POSTGRES")
  POSTGRES_PORT: $(quote "$POSTGRES_PORT")
  POSTGRES_DB: $(quote "$POSTGRES_DB")
  APP_PUBLIC_BASE_URL: $(quote "$APP_PUBLIC_BASE_URL")
  JWT_ISSUER: $(quote "$JWT_ISSUER")
  JWT_EXPIRATION_HOURS: "8"
MANIFEST
