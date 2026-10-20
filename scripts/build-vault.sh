#!/bin/bash
set -eu
cd "$(dirname "$0")/../third_party/libgocryptfs"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ]; then SDK_ROOT=$(sed -n 's/^sdk.dir=//p' ../../local.properties); fi
NDK_DIR="$SDK_ROOT/ndk/30.0.16248370"
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64;; Linux) HOST_TAG=linux-x86_64;; *) echo 'Unsupported build host' >&2; exit 1;; esac
for ABI in "${@:-arm64-v8a}"; do
    case "$ABI" in
        arm64-v8a) ARCH=arm64; COMPILER=aarch64-linux-android23-clang;;
        armeabi-v7a) ARCH=arm; COMPILER=armv7a-linux-androideabi23-clang;;
        x86) ARCH=386; COMPILER=i686-linux-android23-clang;;
        x86_64) ARCH=amd64; COMPILER=x86_64-linux-android23-clang;;
        *) echo "Unknown ABI: $ABI" >&2; exit 1;;
    esac
    mkdir -p "build/$ABI"
    CGO_ENABLED=1 GOOS=android GOARCH="$ARCH" GOARM=7 \
      CC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin/$COMPILER" \
      CGO_LDFLAGS='-Wl,-soname,libgocryptfs.so -Wl,-z,max-page-size=16384' \
      go build -tags without_openssl -trimpath -buildmode=c-shared -o "build/$ABI/libgocryptfs.so" .
done
