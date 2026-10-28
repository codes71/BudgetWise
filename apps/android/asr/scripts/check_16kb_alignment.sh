#!/usr/bin/env bash
# Usage: check_16kb_alignment.sh <apk>
# Fails unless the APK passes zipalign's 16 KB check and every lib/**/*.so has LOAD segment alignment >= 2**14.
set -euo pipefail

APK="${1:?usage: $0 <apk>}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
ZIPALIGN="${BT}zipalign"
OBJDUMP="$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-objdump | sort -V | tail -1)"

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
echo "zipalign: $ZIPALIGN"
"$ZIPALIGN" -v -c -P 16 4 "$APK"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" 'lib/*' -d "$TMP" || true

fail=0
count=0
while IFS= read -r so; do
  count=$((count + 1))
  aligns="$("$OBJDUMP" -p "$so" | awk '$1 == "LOAD" { for (i = 1; i <= NF; i++) if ($i == "align") print $(i + 1) }')"
  if [ -z "$aligns" ]; then
    echo "FAIL ${so#"$TMP"/}: no LOAD segments found"
    fail=1
    continue
  fi
  for a in $aligns; do
    exp="$(printf '%s' "$a" | sed -n 's/^2\*\*\([0-9][0-9]*\)$/\1/p')"
    if [ -z "$exp" ] || [ "$exp" -lt 14 ]; then
      echo "FAIL ${so#"$TMP"/}: LOAD align $a (< 2**14)"
      fail=1
    fi
  done
  [ "$fail" -eq 0 ] && echo "ok   ${so#"$TMP"/}"
done < <(find "$TMP/lib" -name '*.so' 2>/dev/null | sort)

[ "$count" -gt 0 ] || { echo "FAIL: no lib/**/*.so in APK" >&2; exit 1; }
[ "$fail" -eq 0 ] && echo "16 KB alignment check passed ($count libs)"
exit "$fail"
