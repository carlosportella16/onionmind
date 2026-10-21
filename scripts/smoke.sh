#!/usr/bin/env bash
# Smoke test (somente GET) contra uma instância do core já rodando — local depois de
# `docker compose up` + core, ou um ambiente implantado. Não escreve dados.
#
# Uso:
#   ./scripts/smoke.sh                                   # http://localhost:8081, todos os @smoke
#   SMOKE_BASE_URL=https://host ./scripts/smoke.sh
#   SMOKE_TAGS='@smoke and not @semantic' ./scripts/smoke.sh   # instância sem embedding.enabled
#   SMOKE_TAGS='@smoke and not @ia' ./scripts/smoke.sh         # instância sem ai.enabled
#
# @somente-leitura compara /api/stats antes e depois: exclua-o se um crawler estiver
# ingerindo na mesma instância durante a execução.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SMOKE_BASE_URL="${SMOKE_BASE_URL:-http://localhost:8081}"
export SMOKE_TAGS="${SMOKE_TAGS:-@smoke}"

echo "==> Smoke contra $SMOKE_BASE_URL (tags: $SMOKE_TAGS)"
exec "$ROOT_DIR/core/gradlew" -p "$ROOT_DIR/core" smokeTest --console=plain
