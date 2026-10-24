# shellcheck shell=bash
#
# Funções que todo script que fala com o ambiente implantado precisa antes de dizer
# qualquer coisa útil: conferir as ferramentas, exigir a credencial de seed e descobrir
# o endereço público da API.
#
# Ficam aqui porque smoke-test.sh e load-test.sh fazem os três passos do mesmo jeito, e
# a parte que mais importa deles são as mensagens de erro — que precisam dizer ao
# operador o que fazer em seguida, e não podem divergir entre um script e outro.
#
# Para ser incluída, não executada:
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/environment.sh"
#
# Quem inclui recebe também SERVICE_NAME, ADMIN_USERNAME e BASE_URL.

SERVICE_NAME="oficina-mecanica"
ADMIN_USERNAME="${APP_SEED_ADMIN_USERNAME:-admin}"
BALANCER_TIMEOUT_SECONDS="${BALANCER_TIMEOUT_SECONDS:-180}"
BALANCER_RETRY_SECONDS=5

# NAMESPACE escolhe o ambiente. Sem ele vale o namespace do contexto atual do kubectl.
kube() {
    kubectl ${NAMESPACE:+--namespace "$NAMESPACE"} "$@"
}

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null || { echo "$tool não encontrado" >&2; exit 1; }
    done
}

require_seed_admin_password() {
    [[ -n "${APP_SEED_ADMIN_PASSWORD:-}" ]] && return

    echo "APP_SEED_ADMIN_PASSWORD não definida." >&2
    echo "É a senha do usuário de seed entregue ao cluster no Secret oficina-mecanica-env:" >&2
    echo "  export APP_SEED_ADMIN_PASSWORD=..." >&2
    exit 1
}

# Com um endereço explícito, é ele. Sem, o endereço sai do balanceador do Service — o
# que dispensa o operador de copiar à mão um hostname de ELB a cada ambiente novo.
# BASE_URL é o resultado, lido por quem incluiu este arquivo.
#
# O endereço não existe no instante em que o Service nasce: o EKS leva cerca de um minuto
# para materializar o ELB e escrever o hostname no status. Esperar aqui é o que faz o
# primeiro deploy num cluster novo terminar em smoke test, e não em "ainda não tem
# endereço" — que é o que o caminho de entrega veria, aplicando os manifestos e chamando
# o smoke test em seguida.
# shellcheck disable=SC2034
resolve_base_url() {
    local given_url="${1:-}" elb_hostname deadline

    if [[ -n "$given_url" ]]; then
        BASE_URL="${given_url%/}"
        return
    fi

    require_tools kubectl
    deadline=$((SECONDS + BALANCER_TIMEOUT_SECONDS))
    while :; do
        elb_hostname="$(kube get svc "$SERVICE_NAME" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
        if [[ -n "$elb_hostname" ]]; then
            BASE_URL="http://$elb_hostname"
            return
        fi
        if ((SECONDS >= deadline)); then
            echo "O Service $SERVICE_NAME não ganhou endereço de balanceador em ${BALANCER_TIMEOUT_SECONDS}s." >&2
            echo "Confira o Service (kubectl describe svc $SERVICE_NAME) ou informe o endereço:" >&2
            echo "  $0 http://meu-endereco" >&2
            exit 1
        fi
        echo "Aguardando o endereço do balanceador do Service $SERVICE_NAME..."
        sleep "$BALANCER_RETRY_SECONDS"
    done
}

login_payload() {
    jq -nc \
        --arg username "$ADMIN_USERNAME" \
        --arg password "$APP_SEED_ADMIN_PASSWORD" \
        '{username: $username, password: $password}'
}

# Gera um CPF válido novo a cada chamada: rodar um script duas vezes seguidas não pode falhar por
# unicidade. Os dígitos verificadores são calculados aqui porque a aplicação recusa documento
# inválido na entrada — é o algoritmo público do CPF, não uma regra desta oficina.
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
