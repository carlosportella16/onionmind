#!/usr/bin/env bash
# End-to-end smoke for Fases 1-4: the real crawler image crawls fake .onion sites through a
# fake Tor, the core indexes, embeds and enriches them (fake Gemini/DeepSeek, real Qdrant), and the
# @e2e + @smoke Cucumber scenarios check it all through the core's API. Same command
# locally and in CI (.github/workflows/e2e.yml). Needs Docker and a free port 8081.
#
# Uso: ./scripts/e2e-smoke.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/e2e/docker-compose.e2e.yml")
GRADLE=("$ROOT_DIR/core/gradlew" -p "$ROOT_DIR/core" --console=plain)
export SMOKE_BASE_URL="http://localhost:8081"

cleanup() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    echo "==> e2e falhou (exit $status) — logs:"
    for svc in crawler core fakes; do
      echo "----- $svc -----"
      "${COMPOSE[@]}" logs --no-color --tail=200 "$svc" || true
    done
  fi
  echo "==> Derrubando a stack e2e..."
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

echo "==> Build do core (jar montado no contêiner)..."
"${GRADLE[@]}" bootJar -q

echo "==> Subindo a stack e2e (crawler e fakes são construídos aqui)..."
"${COMPOSE[@]}" up -d --build

echo "==> Esperando o core ficar saudável..."
for _ in $(seq 1 90); do
  curl -sf "$SMOKE_BASE_URL/actuator/health" >/dev/null && break
  sleep 2
done
curl -sf "$SMOKE_BASE_URL/actuator/health" >/dev/null || { echo "core não ficou saudável em 180s"; exit 1; }

echo "==> Cenários @e2e (Fases 1-4 ponta a ponta)..."
SMOKE_TAGS='@e2e' "${GRADLE[@]}" smokeTest

echo "==> Parando o crawler — tem que sair com código 0 (SIGTERM tratado)..."
"${COMPOSE[@]}" stop crawler
exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$("${COMPOSE[@]}" ps -aq crawler)")
[[ "$exit_code" = "0" ]] || { echo "crawler saiu com código $exit_code, esperado 0"; exit 1; }

# Crawler já parado: @somente-leitura não pode ver a stats mudando no meio da execução.
echo "==> Smoke completo (@smoke, incluindo @semantic e @ia)..."
SMOKE_TAGS='@smoke' "${GRADLE[@]}" smokeTest

echo "==> e2e OK"
