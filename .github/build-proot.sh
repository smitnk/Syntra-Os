#!/bin/bash
# Builds Syntra's proot from Termux's maintained source with the runner's Android NDK.
set -e
J=$PWD/app/src/main/jniLibs/arm64-v8a; mkdir -p $J
W=$PWD/proot-build; mkdir -p $W; cd $W

# Fallback: green-green-avk's prebuilt Android-app proot.
git clone -q https://github.com/green-green-avk/build-proot-android bpa && git -C bpa checkout -q 01f83b8841358450c78333d1b33ab30d4943bec4
mkdir -p gg && tar xzf bpa/packages/proot-android-aarch64.tar.gz -C gg
cp gg/root/bin/proot $J/libproot-gg.so; cp gg/root/libexec/proot/loader $J/libproot-gg-loader.so

# talloc: Termux's package (headers + shared lib), so no cross-compiling of talloc's waf build.
M=https://packages.termux.dev/apt/termux-main
curl -fsSL $M/dists/stable/main/binary-aarch64/Packages -o Packages || { M=https://cdimage.debian.org/mirror/termux.dev/apt/termux-main; curl -fsSL $M/dists/stable/main/binary-aarch64/Packages -o Packages; }
deb() { awk -v p="$1" '$1=="Package:"{x=($2==p)} x&&$1=="Filename:"{print $2; exit}' Packages; }
mkdir -p tx; f=$(deb libtalloc); echo "libtalloc -> $f"; curl -fsSL "$M/$f" -o talloc.deb; dpkg-deb -x talloc.deb tx
U=$W/tx/data/data/com.termux/files/usr
ls $U/include/talloc.h $U/lib/libtalloc.so*

# proot from Termux source; loader unbundled so it runs from nativeLibraryDir (Android blocks exec from app data).
TC=$ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC=$TC/aarch64-linux-android24-clang STRIP=$TC/llvm-strip OBJCOPY=$TC/llvm-objcopy OBJDUMP=$TC/llvm-objdump
git clone -q https://github.com/termux/proot tp && git -C tp checkout -q b27d81a68d765f3462e807f4a14e01a03f8e89ea
export CFLAGS="-I$U/include -DARG_MAX=131072"
# Upstream ashmem_memfd.c misses string.h; newer NDK clang rejects implicit declarations.
sed -i '1i #include <string.h>' tp/src/extension/ashmem_memfd/ashmem_memfd.c
export LDFLAGS="-L$U/lib"
make -C tp/src V=1 PROOT_UNBUNDLE_LOADER=. HAS_LOADER_32BIT= proot loader/loader
$STRIP tp/src/proot tp/src/loader/loader
cp tp/src/proot $J/libproot.so; cp tp/src/loader/loader $J/libproot-loader.so
cp $(ls $U/lib/libtalloc.so.* | head -1) $J/libtalloc.so
sudo apt-get install -y -qq patchelf >/dev/null
patchelf --replace-needed libtalloc.so.2 libtalloc.so --remove-rpath $J/libproot.so
patchelf --set-soname libtalloc.so $J/libtalloc.so
$TC/llvm-readelf -d $J/libproot.so | grep -E "NEEDED|RUNPATH" || true
ls -la $J
