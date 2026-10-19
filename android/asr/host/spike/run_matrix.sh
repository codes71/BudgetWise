#!/usr/bin/env bash
# Usage: run_matrix.sh <llama-bin-dir> <cache-dir> <clips.jsonl>
# Serves each config on :8089, runs auto and forced, writes golden/<label>-<mode>.jsonl, stops the server.
set -uo pipefail
BIN=${1:?bin dir}; CACHE=${2:?cache dir}; CLIPS=${3:?clips.jsonl}
HERE=$(cd "$(dirname "$0")" && pwd)
CONFIGS=(
  "raj-0.6b-q8_0|$CACHE/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf|$CACHE/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf"
  "raj-0.6b-q5_k_m|$CACHE/raj-0.6b/gguf/Raj-0.6B-Q5_K_M.gguf|$CACHE/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf"
  "raj-0.6b-q4_k_m|$CACHE/raj-0.6b/gguf/Raj-0.6B-Q4_K_M.gguf|$CACHE/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf"
  "raj-1.7b-q8_0|$CACHE/raj-1.7b/gguf/Raj-1.7B-Q8_0.gguf|$CACHE/raj-1.7b/gguf/mmproj-Raj-1.7B-Q8_0.gguf"
)
ONLY=${ONLY:-}
for cfg in "${CONFIGS[@]}"; do
  IFS='|' read -r label model mmproj <<<"$cfg"
  [ -n "$ONLY" ] && [ "$ONLY" != "$label" ] && continue
  "$BIN/llama-server" -m "$model" --mmproj "$mmproj" -c 4096 --temp 0 --port 8089 \
    >"$CACHE/server-$label.log" 2>&1 &
  pid=$!
  until curl -sf http://127.0.0.1:8089/health >/dev/null; do
    kill -0 $pid 2>/dev/null || { echo "server for $label died"; break; }; sleep 1; done
  for mode in auto forced; do
    python3 -I "$HERE/run_spike.py" --server http://127.0.0.1:8089 --clips "$CLIPS" \
      --mode $mode --label "$label" --out "$HERE/golden/$label-$mode.jsonl" 2>"$CACHE/run-$label-$mode.log" | tail -1
  done
  kill $pid; wait $pid 2>/dev/null
done
