#!/bin/bash
set -e
J=$PWD/app/src/main/jniLibs/arm64-v8a; mkdir -p $J
git clone -q https://github.com/green-green-avk/build-proot-android bpa && git -C bpa checkout -q 01f83b8841358450c78333d1b33ab30d4943bec4
# Fallback: green-green-avk's prebuilt Android-app proot.
mkdir -p t && tar xzf bpa/packages/proot-android-aarch64.tar.gz -C t
cp t/root/bin/proot $J/libproot-gg.so; cp t/root/libexec/proot/loader $J/libproot-gg-loader.so
# Static talloc via green-green-avk's cross-compile recipe, for aarch64 only, with this runner's NDK.
cd bpa
sed -i "s|^NDK=.*|NDK=\"$ANDROID_NDK_LATEST_HOME\"|; s|^ARCHS=.*|ARCHS='aarch64'|" config
./get-talloc.sh  && ./make-talloc-static.sh 
. ./config; set-arch aarch64
# Termux's maintained proot (knows the syscalls Ubuntu 24.04 uses), built for Syntra: loader unbundled
# so it runs from nativeLibraryDir (Android blocks executing files from app data).
git clone -q https://github.com/termux/proot tp && git -C tp checkout -q b27d81a68d765f3462e807f4a14e01a03f8e89ea
export CFLAGS="-I$STATIC_ROOT/include -DARG_MAX=131072 -Werror=implicit-function-declaration"
export LDFLAGS="-L$STATIC_ROOT/lib"
make -C tp/src V=1 PROOT_UNBUNDLE_LOADER=. HAS_LOADER_32BIT= proot loader/loader 
"$STRIP" tp/src/proot tp/src/loader/loader
cp tp/src/proot $J/libproot.so; cp tp/src/loader/loader $J/libproot-loader.so
cd ..; ls -la $J; file $J/* || true
