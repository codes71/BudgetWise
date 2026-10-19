#!/usr/bin/env bash
# Usage: build_android_cli.sh [build-root]
# Cross-compiles the pinned llama.cpp (android/asr/third_party/llama.cpp) for arm64-v8a with the NDK and
# installs llama-mtmd-cli plus the ggml CPU variant .so files. Output stays outside the repo.
set -euo pipefail

ROOT=${1:-$HOME/.cache/budgetwise-eval/android-cli}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../../third_party/llama.cpp" && pwd)
PIN=fb34fc262c1b43f1832c7472429fb2247d650493
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
ANDROID_NDK=${ANDROID_NDK:-$SDK/ndk/29.0.13113456}
CMAKE_BIN=$SDK/cmake/3.31.6/bin

test "$(git -C "$SRC" rev-parse HEAD)" = "$PIN" || { echo "llama.cpp is not at $PIN" >&2; exit 1; }
test -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" || { echo "NDK not found: $ANDROID_NDK" >&2; exit 1; }
test -x "$CMAKE_BIN/cmake" || { echo "cmake not found: $CMAKE_BIN" >&2; exit 1; }

export PATH="$CMAKE_BIN:$PATH"
BUILD=$ROOT/build
INSTALL=$ROOT/install
cmake -S "$SRC" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
  -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_CPU_KLEIDIAI=ON \
  -DBUILD_SHARED_LIBS=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_MTMD=ON \
  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF

cmake --build "$BUILD" --target llama-mtmd-cli -j"$(sysctl -n hw.ncpu)"

# Only llama-mtmd-cli is built, so copy it and the shared libs (including the dlopen'd CPU variants) by hand.
rm -rf "$INSTALL"
mkdir -p "$INSTALL/bin" "$INSTALL/lib"
cp "$BUILD/bin/llama-mtmd-cli" "$INSTALL/bin/"
find "$BUILD/bin" -maxdepth 1 -name '*.so' ! -name 'libggml-cpu-*' -exec cp {} "$INSTALL/lib/" \;
# ggml only searches the executable's directory for dlopen'd backends, so the CPU variants live in bin/.
find "$BUILD/bin" -maxdepth 1 -name 'libggml-cpu-*.so' -exec cp {} "$INSTALL/bin/" \;
STRIP=$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
"$STRIP" --strip-unneeded "$INSTALL"/bin/* "$INSTALL"/lib/*

test -f "$INSTALL/bin/llama-mtmd-cli" || { echo "missing llama-mtmd-cli" >&2; exit 1; }
ls "$INSTALL"/bin/libggml-cpu-*.so >/dev/null || { echo "missing ggml CPU variant .so files in bin/" >&2; exit 1; }
file "$INSTALL/bin/llama-mtmd-cli" | grep -q 'ARM aarch64' || { echo "llama-mtmd-cli is not arm64" >&2; exit 1; }
echo "installed: $INSTALL"
