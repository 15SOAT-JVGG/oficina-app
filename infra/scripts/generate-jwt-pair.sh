#!/usr/bin/env bash
# Gera o par RSA usado para assinar e validar os JWT (RS256) da aplicação.
#
# O par é gerado UMA ÚNICA VEZ, fora do build da imagem, e entregue à aplicação por
# JWT_PRIVATE_KEY_LOCATION e JWT_PUBLIC_KEY_LOCATION. Regerar o par invalida todos os
# tokens já emitidos, então só gere de novo em rotação planejada.
#
# Uso:
#   infra/scripts/generate-jwt-pair.sh                 # ambiente local (.local-jwt/)
#   infra/scripts/generate-jwt-pair.sh <diretório>     # outro destino
#
# Onde guardar:
#   - Local: .local-jwt/ (ignorado pelo git). Não versione, não reutilize em produção.
#   - Ambientes: nos secrets do GitHub Environment (homolog / production), como
#     JWT_PRIVATE_KEY_B64 / JWT_PUBLIC_KEY_B64, de onde a pipeline cria o Secret do
#     Kubernetes montado no pod. O oficina-lambda-auth assina com a mesma chave privada,
#     então ela também vai para o Environment daquele repositório.
set -euo pipefail

TARGET_DIR="${1:-.local-jwt}"
KEY_SIZE_BITS=2048

command -v openssl >/dev/null || { echo "openssl não encontrado" >&2; exit 1; }

PRIVATE_PEM="$TARGET_DIR/privateKey.pem"
PUBLIC_PEM="$TARGET_DIR/publicKey.pem"

if [[ -s "$PRIVATE_PEM" ]]; then
    echo "Par já existe em $TARGET_DIR — nada a fazer."
    echo "Para rotacionar, apague o diretório e rode de novo (invalida os tokens em circulação)."
    exit 0
fi

mkdir -p "$TARGET_DIR"
umask 077
openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:$KEY_SIZE_BITS" -out "$PRIVATE_PEM"
openssl rsa -pubout -in "$PRIVATE_PEM" -out "$PUBLIC_PEM"
chmod 600 "$PRIVATE_PEM"
chmod 644 "$PUBLIC_PEM"

echo "Par RS256 gerado em $TARGET_DIR:"
echo "  JWT_PRIVATE_KEY_LOCATION=$PRIVATE_PEM"
echo "  JWT_PUBLIC_KEY_LOCATION=$PUBLIC_PEM"
echo
echo "Para guardar nos secrets de um ambiente (homolog ou production), uma linha cada:"
echo "  gh secret set JWT_PRIVATE_KEY_B64 --env <ambiente> --body \"\$(base64 < $PRIVATE_PEM | tr -d '\\n')\""
echo "  gh secret set JWT_PUBLIC_KEY_B64  --env <ambiente> --body \"\$(base64 < $PUBLIC_PEM | tr -d '\\n')\""
