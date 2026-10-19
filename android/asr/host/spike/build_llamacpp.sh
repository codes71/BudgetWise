#!/usr/bin/env bash
# Usage: build_llamacpp.sh <dest>
# Clones llama.cpp, checks out the pinned commit and builds the host tools (Metal on macOS).
set -euo pipefail

PIN=fb34fc262c1b43f1832c7472429fb2247d650493
DEST=${1:?usage: build_llamacpp.sh <dest>}
CMAKE=${CMAKE:-$(command -v cmake || ls "$HOME"/Library/Android/sdk/cmake/3.31.6/bin/cmake)}
NINJA_DIR=$(dirname "$CMAKE")

if [ ! -d "$DEST/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp "$DEST"
fi
git -C "$DEST" fetch --quiet origin "$PIN" 2>/dev/null || git -C "$DEST" fetch --quiet origin
git -C "$DEST" checkout --quiet "$PIN"
test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"

export PATH="$NINJA_DIR:$PATH"
"$CMAKE" -S "$DEST" -B "$DEST/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_MTMD=ON \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF
"$CMAKE" --build "$DEST/build" --target llama-server llama-mtmd-cli llama-quantize -j"$(sysctl -n hw.ncpu)"
echo "built: $DEST/build/bin"
