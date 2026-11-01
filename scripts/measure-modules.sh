#!/usr/bin/env bash
# Samples a running stack for the Phase 5 module-extraction review
# (openspec change phase5-module-extraction-review, task 1.2). Read-only: it scrapes
# /actuator/prometheus, docker stats / ps and runs SELECT count(*) on the dev Postgres.
#
# Uso:
#   ./scripts/measure-modules.sh [-i SECONDS] [-d SECONDS] [-o DIR] [--prom-file FILE]...
#     -i  sample interval (default 15)
#     -d  duration (default 7200 = 2 h)
#     -o  output dir (default measure-<timestamp>); writes samples.csv, first.prom,
#         last.prom and summary.txt
#     --prom-file  read this file instead of scraping (offline test); repeat it to feed
#         one file per sample, the last one repeats
#
#   CORE_URL           default http://localhost:8081
#   CORE_CONTAINER     core container name; if unset the core is the process listening
#   CORE_PID           on CORE_URL's port (or this PID)
#   CRAWLER_CONTAINER  default: first running container of image onionmind-crawler
#   PG_CONTAINER       default onionmind-postgres-1; empty skips the DB counts
#
# p50/p95 per endpoint need the latency histogram, which is off by default. Start the
# core with (no code change) the env var below, or the argument
# --management.metrics.distribution.percentiles-histogram.http.server.requests=true:
#   MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS=true
set -euo pipefail

INTERVAL=15 DURATION=7200 OUT="measure-$(date +%Y%m%d-%H%M%S)"
PROM_FILES=()
while (($#)); do
  case "$1" in
    -i) INTERVAL="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --prom-file) PROM_FILES+=("$2"); shift 2 ;;
    *) sed -n '2,24p' "$0"; exit 2 ;;
  esac
done

CORE_URL="${CORE_URL:-http://localhost:8081}"
PG_CONTAINER="${PG_CONTAINER-onionmind-postgres-1}"
CRAWLER_CONTAINER="${CRAWLER_CONTAINER:-$(docker ps -q --filter ancestor=onionmind-crawler 2>/dev/null | head -1)}"
if [[ -z "${CORE_CONTAINER:-}" && -z "${CORE_PID:-}" ]]; then
  port="${CORE_URL##*:}"; port="${port%%/*}"
  CORE_PID="$(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
fi

mkdir -p "$OUT"
CSV="$OUT/samples.csv"
SEARCH='uri="/api/search"' CHAT='uri="/api/chat"'

# prom_sum FILE METRIC [LABEL_FRAGMENT]: sum of every series of METRIC (optionally only
# those whose labels contain LABEL_FRAGMENT). Empty when the metric is absent.
prom_sum() {
  awk -v m="$2" -v f="${3:-}" '
    (index($0, m "{") == 1 || index($0, m " ") == 1) && (f == "" || index($0, f)) { s += $NF; n++ }
    END { if (n) printf "%.10g", s }' "$1"
}

# docker_stats NAME -> "cpu_pct,mem_mib"
docker_stats() {
  [[ -n "$1" ]] || { echo ","; return; }
  docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$1" 2>/dev/null | awk '
    { cpu = $1; sub(/%/, "", cpu); mem = $2; v = mem + 0; u = mem; sub(/^[0-9.]+/, "", u)
      f = (u == "GiB") ? 1024 : (u == "KiB" || u == "kB") ? 1/1024 : (u == "B") ? 1/1048576 : 1
      printf "%s,%.1f", cpu, v * f; ok = 1 }
    END { if (!ok) printf "," }'
}

core_stats() {
  if [[ -n "${CORE_CONTAINER:-}" ]]; then docker_stats "$CORE_CONTAINER"
  elif [[ -n "${CORE_PID:-}" ]]; then
    # ps %cpu is a decaying average on macOS, a lifetime average on Linux: prefer
    # jvm_cpu_pct there.
    ps -o %cpu=,rss= -p "$CORE_PID" 2>/dev/null | awk '{ printf "%s,%.1f", $1, $2 / 1024; ok = 1 } END { if (!ok) printf "," }'
  else echo ","; fi
}

START=$(date +%s)
db_counts() {
  [[ -n "$PG_CONTAINER" ]] || { echo ",,,,"; return; }
  docker exec "$PG_CONTAINER" psql -U onionmind -d onionmind -tAF, -c "
    SELECT (SELECT count(*) FROM pages),
           (SELECT count(*) FROM pages WHERE last_seen_at >= to_timestamp($START)),
           (SELECT count(*) FROM pages WHERE ai_status IN ('pending', 'failed_transient')),
           (SELECT count(*) FROM pages WHERE embedding_status IN ('pending', 'failed_transient')
                                          AND extracted_text IS NOT NULL),
           (SELECT count(*) FROM page_changes WHERE status IN ('pending', 'failed_transient'))" \
    2>/dev/null || echo ",,,,"
}

echo "ts,core_cpu_pct,core_mem_mib,crawler_cpu_pct,crawler_mem_mib,jvm_cpu_pct,jvm_heap_mib,search_count,search_sum_s,chat_count,chat_sum_s,ai_requests,ai_duration_count,ai_duration_sum_s,ingestion_ai_lag,pages_total,pages_seen,ai_pending,embedding_pending,changes_pending" > "$CSV"
echo "==> sampling every ${INTERVAL}s for ${DURATION}s into $OUT (core pid=${CORE_PID:-} container=${CORE_CONTAINER:-}, crawler=${CRAWLER_CONTAINER:-none})"

k=0
while :; do
  now=$(date +%s)
  prom="$OUT/last.prom"
  if ((${#PROM_FILES[@]})); then
    i=$((k < ${#PROM_FILES[@]} ? k : ${#PROM_FILES[@]} - 1))
    cp "${PROM_FILES[$i]}" "$prom"
  else
    curl -fsS --max-time 10 "$CORE_URL/actuator/prometheus" -o "$prom" || : > "$prom"
  fi
  ((k)) || cp "$prom" "$OUT/first.prom"

  ncpu=$(getconf _NPROCESSORS_ONLN)
  jvm_cpu=$(prom_sum "$prom" process_cpu_usage); [[ -n "$jvm_cpu" ]] && jvm_cpu=$(awk -v c="$jvm_cpu" -v n="$ncpu" 'BEGIN { printf "%.1f", c * n * 100 }')
  heap=$(prom_sum "$prom" jvm_memory_used_bytes 'area="heap"'); [[ -n "$heap" ]] && heap=$(awk -v b="$heap" 'BEGIN { printf "%.1f", b / 1048576 }')

  echo "$now,$(core_stats),$(docker_stats "$CRAWLER_CONTAINER"),$jvm_cpu,$heap,$(prom_sum "$prom" http_server_requests_seconds_count "$SEARCH"),$(prom_sum "$prom" http_server_requests_seconds_sum "$SEARCH"),$(prom_sum "$prom" http_server_requests_seconds_count "$CHAT"),$(prom_sum "$prom" http_server_requests_seconds_sum "$CHAT"),$(prom_sum "$prom" ai_request_total),$(prom_sum "$prom" ai_request_duration_seconds_count),$(prom_sum "$prom" ai_request_duration_seconds_sum),$(prom_sum "$prom" ingestion_ai_lag),$(db_counts)" >> "$CSV"

  k=$((k + 1))
  (( now + INTERVAL - START < DURATION )) || break
  sleep "$INTERVAL"
done

# quantile Q LABEL_FRAGMENT: run-wide quantile from the histogram bucket deltas
# between first.prom and last.prom, linear inside the bucket (as histogram_quantile does).
quantile() {
  awk -v q="$1" -v f="$2" '
    index($0, "http_server_requests_seconds_bucket{") == 1 && index($0, f) {
      match($0, /le="[^"]*"/); le = substr($0, RSTART + 4, RLENGTH - 5)
      v = (FILENAME == ARGV[2]) ? $NF : -$NF
      if (le == "+Inf") total += v; else d[le + 0] += v }
    END {
      if (total == "") { print "n/a"; exit }
      if (total <= 0) { print "n/a (no requests)"; exit }
      n = 0; for (le in d) b[++n] = le + 0
      for (i = 2; i <= n; i++) for (j = i; j > 1 && b[j-1] > b[j]; j--) { t = b[j]; b[j] = b[j-1]; b[j-1] = t }
      rank = q * total; prev = 0; lo = 0
      for (i = 1; i <= n; i++) {
        c = d[b[i]]
        if (c >= rank) { printf "%.0f ms", 1000 * (lo + (b[i] - lo) * (c > prev ? (rank - prev) / (c - prev) : 1)); exit }
        prev = c; lo = b[i]
      }
      printf "> %.0f ms", 1000 * b[n] }' "$OUT/first.prom" "$OUT/last.prom"
}

{
  echo "Measurement $(date -r "$START" '+%Y-%m-%d %H:%M' 2>/dev/null || date -d "@$START" '+%Y-%m-%d %H:%M'), ${k} samples every ${INTERVAL}s"
  awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) h[i] = $i; next }
    NR == 2 { for (i = 1; i <= NF; i++) { first[i] = $i; mx[i] = $i } }
    { for (i = 2; i <= 7; i++) if ($i != "") { s[i] += $i; n[i]++; if ($i > mx[i]) mx[i] = $i }
      for (i = 15; i <= 20; i++) if ($i != "" && $i > mx[i]) mx[i] = $i
      for (i = 1; i <= NF; i++) last[i] = $i }
    function delta(i) { return last[i] - first[i] }
    function mean_ms(c, s) { return delta(c) > 0 ? sprintf("%.0f ms (%d req)", 1000 * delta(s) / delta(c), delta(c)) : "n/a (no requests)" }
    END {
      for (i = 2; i <= 7; i++) printf "%-16s mean %s  max %s\n", h[i], n[i] ? sprintf("%.1f", s[i] / n[i]) : "n/a", n[i] ? mx[i] : "n/a"
      min = (last[1] - first[1]) / 60
      printf "/api/search      mean %s\n", mean_ms(8, 9)
      printf "/api/chat        mean %s\n", mean_ms(10, 11)
      printf "ai requests      %s in the run, mean %s\n", delta(12), mean_ms(13, 14)
      if (min > 0 && last[16] != "") printf "pages/min        %.2f new, %.2f seen (new + revisited)\n", delta(16) / min, last[17] / min
      for (i = 15; i <= 20; i++) if (i != 16 && i != 17 && last[i] != "") printf "%-16s first %s  last %s  max %s\n", h[i], first[i], last[i], mx[i]
    }' "$CSV"
  for ep in "/api/search:$SEARCH" "/api/chat:$CHAT"; do
    printf '%-16s p50 %s  p95 %s\n' "${ep%%:*}" "$(quantile 0.5 "${ep#*:}")" "$(quantile 0.95 "${ep#*:}")"
  done
  grep -q '^http_server_requests_seconds_bucket' "$OUT/last.prom" ||
    echo "p50/p95 n/a: start the core with MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS=true"
} | tee "$OUT/summary.txt"
