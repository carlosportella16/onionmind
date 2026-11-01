#!/usr/bin/env bash
# Downloads the local embedding model (multilingual-e5-small int8 + its SentencePiece tokenizer)
# that the core loads from embedding.model-dir. URLs are pinned to a Hugging Face revision and
# every file is checked against its sha256: a mismatch deletes the file and fails. Files already
# present are not downloaded again.
#
# Uso: ./scripts/fetch-embedding-model.sh [dir]
#      (default dir: ~/.cache/onionmind/models/multilingual-e5-small, the core's default)
set -euo pipefail

DIR=${1:-$HOME/.cache/onionmind/models/multilingual-e5-small}
HF=https://huggingface.co
ONNX_REV=761b726dd34fb83930e26aab4e9ac3899aa1fa78   # Xenova/multilingual-e5-small
SP_REV=614241f622f53c4eeff9890bdc4f31cfecc418b3     # intfloat/multilingual-e5-small

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

fetch() { # url file sha256
  local url=$1 file=$DIR/$2 want=$3
  if [[ ! -s "$file" ]]; then
    echo "==> Baixando $2..."
    curl -fsSL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  local got
  got=$(sha256 "$file")
  if [[ "$got" != "$want" ]]; then
    rm -f "$file"
    echo "sha256 de $2 não confere (esperado $want, veio $got) — arquivo apagado" >&2
    exit 1
  fi
  echo "ok $2"
}

mkdir -p "$DIR"
fetch "$HF/Xenova/multilingual-e5-small/resolve/$ONNX_REV/onnx/model_quantized.onnx" model_quantized.onnx \
  f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193
fetch "$HF/intfloat/multilingual-e5-small/resolve/$SP_REV/sentencepiece.bpe.model" sentencepiece.bpe.model \
  cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865
