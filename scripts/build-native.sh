#!/usr/bin/env bash
# Builds llama.cpp's server for Android arm64 and puts it in app/src/main/jniLibs/arm64-v8a:
#   libllama_cpu.so  - CPU only (always built)
#   libllama_gpu.so  - Vulkan GPU build (optional; if it fails the app is simply CPU-only)
# The files are normal executables named lib*.so so Android installs them next to the app.
set -uo pipefail

LLAMA_TAG="${LLAMA_TAG:-v1.0.0}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK:-}}}"
[ -d "$NDK" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)"; exit 1; }

ROOT="$(pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$ROOT/.native"
mkdir -p "$OUT" "$WORK"
cd "$WORK"

[ -d llama.cpp/.git ] || git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp || exit 1

HOST="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
GEN=()
command -v ninja >/dev/null 2>&1 && GEN=(-G Ninja)

COMMON=(
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static
  -DBUILD_SHARED_LIBS=OFF
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF
  -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON
  -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF
)

build() { # build <name> <extra cmake args...>
  local name="$1"; shift
  echo "=== building $name ==="
  rm -rf "build-$name"
  cmake -S llama.cpp -B "build-$name" "${GEN[@]}" "${COMMON[@]}" "$@" || return 1
  cmake --build "build-$name" --target llama-server -j "$(nproc)" || return 1
  cp "build-$name/bin/llama-server" "$OUT/libllama_$name.so" || return 1
  "$HOST/bin/llvm-strip" "$OUT/libllama_$name.so" || true
  return 0
}

# CPU build: try with KleidiAI (faster kernels), then without.
build cpu -DGGML_CPU_KLEIDIAI=ON || build cpu -DGGML_CPU_KLEIDIAI=OFF || exit 1

# GPU (Vulkan) build: optional.
[ -d Vulkan-Headers ] || git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers || true
build gpu -DGGML_VULKAN=ON -DGGML_CPU_KLEIDIAI=OFF \
  -DVulkan_LIBRARY="$HOST/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so" \
  -DVulkan_INCLUDE_DIR="$WORK/Vulkan-Headers/include" \
  -DVulkan_GLSLC_EXECUTABLE="$NDK/shader-tools/linux-x86_64/glslc" \
  || echo "WARNING: the Vulkan (GPU) build failed. The app will work in CPU mode only."

ls -la "$OUT"
test -f "$OUT/libllama_cpu.so"
