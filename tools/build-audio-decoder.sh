#!/usr/bin/env bash
# Rebuild bundled audio-only FFmpeg JNI libraries on Linux. Requires curl, tar, make and Android NDK/CMake.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
NDK="$ANDROID_SDK_ROOT/ndk/26.1.10909125"
CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
NINJA="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja"
WORK="${OMNI_AUDIO_BUILD_DIR:-$ROOT/.audio-build}"
mkdir -p "$WORK"
cd "$WORK"
if [[ ! -d media-1.11.1 ]]; then
  curl -fL https://github.com/androidx/media/archive/refs/tags/1.11.1.tar.gz -o media.tar.gz
  tar -xzf media.tar.gz
fi
if [[ ! -d ffmpeg-6.0.1 ]]; then
  curl -fL https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz -o ffmpeg.tar.xz
  tar -xJf ffmpeg.tar.xz
fi
MODULE="$WORK/media-1.11.1/libraries/decoder_ffmpeg/src/main"
[[ -e "$MODULE/jni/ffmpeg" ]] || ln -s "$WORK/ffmpeg-6.0.1" "$MODULE/jni/ffmpeg"
# No GPL/nonfree/external codecs. Static FFmpeg objects are linked into a separately replaceable LGPL JNI .so.
bash "$MODULE/jni/build_ffmpeg.sh" "$MODULE" "$NDK" linux-x86_64 23 \
  dca ac3 eac3 truehd mlp aac aac_latm mp3 mp2 flac alac opus vorbis
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  "$CMAKE" -S "$MODULE/jni" -B "$WORK/jni-build/$abi" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-23 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
  "$CMAKE" --build "$WORK/jni-build/$abi"
  mkdir -p "$ROOT/android/player/src/main/jniLibs/$abi"
  cp "$WORK/jni-build/$abi/libffmpegJNI.so" "$ROOT/android/player/src/main/jniLibs/$abi/"
done
