#!/usr/bin/env bash
# Usage: bench_phone.sh <serial> <label> <gguf> <mmproj> <clipdir> <threads>
#        bench_phone.sh --self-test
# Runs the stock llama-mtmd-cli (built by build_android_cli.sh) on the phone over adb, once per clip, and prints
# CSV: id,audio_ms,wall_ms,rtf,vmhwm_kb. Device facts and the loaded CPU variant go to stderr as '#' lines.
# CLIP_IDS (space separated, default: the 10 G2 clips) selects the wavs from <clipdir>. INSTALL overrides the
# install tree. Greedy auto mode, same arguments as parity_cli.py.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REMOTE=/data/local/tmp/raj
CSV_HEADER="id,audio_ms,wall_ms,rtf,vmhwm_kb"
DEFAULT_CLIPS="fleurs_02 fleurs_10 fleurs_08 fleurs_17 fleurs_23 fleurs_03 fleurs_13 fleurs_00 fleurs_25 fleurs_20"

# Runs on the phone, one clip per call: starts the CLI, polls VmHWM every 200 ms, prints the CLI log then markers.
read -r -d '' DEVICE_RUNNER <<'RUNNER' || true
#!/system/bin/sh
# args: threads model mmproj wav
cd /data/local/tmp/raj
export LD_LIBRARY_PATH=lib
start=$(date +%s%N)
./bin/llama-mtmd-cli -m "$2" --mmproj "$3" --audio "$4" -p "<__media__>" --temp 0 -n 256 -t "$1" >/data/local/tmp/raj/run.out 2>/data/local/tmp/raj/run.err </dev/null &
pid=$!
hwm=0
while kill -0 "$pid" 2>/dev/null; do
  v=$(grep VmHWM "/proc/$pid/status" 2>/dev/null | tr -dc '0-9')
  if [ -n "$v" ] && [ "$v" -gt "$hwm" ]; then hwm=$v; fi
  sleep 0.2
done
rc=0
wait "$pid" || rc=$?
end=$(date +%s%N)
cat /data/local/tmp/raj/run.err
cat /data/local/tmp/raj/run.out
echo "BENCH_WALL_NS=$((end - start))"
echo "BENCH_VMHWM_KB=$hwm"
echo "BENCH_EXIT=$rc"
RUNNER

# parse_run <log> <id> <audio_ms>: prints one CSV row; returns 3 on SIGILL, 4 on a missing marker or non-zero exit.
parse_run() {
  local log=$1 id=$2 audio_ms=$3 wall_ns hwm rc
  wall_ns=$(sed -n 's/^BENCH_WALL_NS=//p' "$log" | tail -1)
  hwm=$(sed -n 's/^BENCH_VMHWM_KB=//p' "$log" | tail -1)
  rc=$(sed -n 's/^BENCH_EXIT=//p' "$log" | tail -1)
  if [ "$rc" = 132 ] || grep -q 'Illegal instruction' "$log"; then
    echo "SIGILL on clip $id (exit $rc)" >&2
    return 3
  fi
  if [ -z "$wall_ns" ] || [ -z "$hwm" ] || [ -z "$rc" ] || [ "$rc" != 0 ]; then
    echo "run failed for clip $id (exit '${rc:-none}')" >&2
    return 4
  fi
  awk -v id="$id" -v a="$audio_ms" -v w="$wall_ns" -v h="$hwm" \
    'BEGIN { wm = w / 1000000; printf "%s,%d,%d,%.2f,%d\n", id, a, wm + 0.5, wm / a, h }'
}

# cpu_variant <log>: file name of the ggml CPU backend the CLI loaded.
cpu_variant() {
  sed -n 's/^load_backend: loaded CPU backend from .*\/\(libggml-cpu-[^/]*\.so\)$/\1/p' "$1" | head -1
}

self_test() {
  local fx=$HERE/fixtures/mtmd_cli_run.log got want variant
  test -f "$fx" || { echo "FAIL: missing fixture $fx" >&2; return 1; }
  got=$(parse_run "$fx" fleurs_23 10200)
  want="fleurs_23,10200,6120,0.60,912384"
  [ "$got" = "$want" ] || { echo "FAIL row: got '$got' want '$want'" >&2; return 1; }
  [ "$CSV_HEADER" = "id,audio_ms,wall_ms,rtf,vmhwm_kb" ] || { echo "FAIL header" >&2; return 1; }
  variant=$(cpu_variant "$fx")
  [ "$variant" = "libggml-cpu-android_armv8.2_2.so" ] || { echo "FAIL variant: '$variant'" >&2; return 1; }
  local tmp; tmp=$(mktemp); trap 'rm -f "$tmp"' RETURN
  sed 's/^BENCH_EXIT=0/BENCH_EXIT=132/' "$fx" >"$tmp"
  if parse_run "$tmp" x 1000 >/dev/null 2>&1; then echo "FAIL: exit 132 not flagged" >&2; return 1; fi
  echo "self-test PASS"
}

wav_ms() {  # 16 kHz mono 16-bit PCM with a 44-byte header
  local bytes; bytes=$(stat -f %z "$1")
  echo $(( (bytes - 44) / 32 ))
}

main() {
  local serial=${1:?serial} label=${2:?label} gguf=${3:?gguf} mmproj=${4:?mmproj} clipdir=${5:?clipdir} threads=${6:?threads}
  local install=${INSTALL:-$HOME/.cache/budgetwise-eval/android-cli/install}
  local ids=${CLIP_IDS:-$DEFAULT_CLIPS} adb=(adb -s "$serial") id wav out
  test -f "$gguf" || { echo "missing $gguf" >&2; exit 1; }
  test -f "$mmproj" || { echo "missing $mmproj" >&2; exit 1; }
  test -x "$install/bin/llama-mtmd-cli" || { echo "missing $install/bin/llama-mtmd-cli (run build_android_cli.sh)" >&2; exit 1; }
  "${adb[@]}" get-state >/dev/null || { echo "adb device $serial not available" >&2; exit 1; }

  "${adb[@]}" shell "mkdir -p $REMOTE/models $REMOTE/clips"
  "${adb[@]}" push "$install/bin" "$install/lib" "$REMOTE/" >/dev/null
  push_if_new() {  # skip the push when the device file already has the same size
    local src=$1 dst=$2 size remote_size
    size=$(stat -f %z "$src")
    remote_size=$("${adb[@]}" shell "stat -c %s '$dst' 2>/dev/null || echo 0" | tr -d '\r')
    [ "$size" = "$remote_size" ] || "${adb[@]}" push "$src" "$dst" >/dev/null
  }
  push_if_new "$gguf" "$REMOTE/models/$(basename "$gguf")"
  push_if_new "$mmproj" "$REMOTE/models/$(basename "$mmproj")"
  "${adb[@]}" shell "chmod 755 $REMOTE/bin/llama-mtmd-cli"
  printf '%s\n' "$DEVICE_RUNNER" >"${TMPDIR:-/tmp}/bench_runner.$$.sh"
  "${adb[@]}" push "${TMPDIR:-/tmp}/bench_runner.$$.sh" "$REMOTE/run_one.sh" >/dev/null
  rm -f "${TMPDIR:-/tmp}/bench_runner.$$.sh"

  {
    echo "# label=$label threads=$threads gguf=$(basename "$gguf")"
    echo "# soc=$("${adb[@]}" shell getprop ro.soc.model | tr -d '\r') hw=$("${adb[@]}" shell getprop ro.hardware | tr -d '\r')"
    echo "# $("${adb[@]}" shell grep MemTotal /proc/meminfo | tr -d '\r')"
    echo "# features:$("${adb[@]}" shell "grep -m1 Features /proc/cpuinfo" | tr -d '\r' | sed 's/^Features *://' | tr ' ' '\n' | grep -E '^(asimddp|i8mm|sve|bf16)$' | sort -u | tr '\n' ' ')"
  } >&2

  echo "$CSV_HEADER"
  local logdir; logdir=$(mktemp -d); local variant_done=0
  for id in $ids; do
    wav=$clipdir/$id.wav
    test -f "$wav" || { echo "missing clip $wav" >&2; exit 1; }
    "${adb[@]}" push "$wav" "$REMOTE/clips/$id.wav" >/dev/null
    "${adb[@]}" shell "sh $REMOTE/run_one.sh $threads models/$(basename "$gguf") models/$(basename "$mmproj") clips/$id.wav" \
      | tr -d '\r' >"$logdir/$id.log" || { echo "adb shell failed on $id" >&2; exit 1; }
    if [ "$variant_done" = 0 ]; then echo "# cpu_variant=$(cpu_variant "$logdir/$id.log")" >&2; variant_done=1; fi
    parse_run "$logdir/$id.log" "$id" "$(wav_ms "$wav")" || exit $?
  done
  echo "# logs: $logdir" >&2
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
else
  main "$@"
fi
