#!/usr/bin/env bash
# Builds Turnip, Mesa's Vulkan driver for Adreno GPUs, for Android's /dev/kgsl-3d0 (Arch's own
# vulkan-freedreno only talks to the desktop msm DRM driver), and writes it, xz-compressed, to
# app/src/main/assets/gpu (not in git), where linux/Gpu.kt installs it from. Run it on aarch64 Arch
# Linux ARM, so the driver links against the same libraries the guest has: in the launcher's own
# guest (about 300 MB of build tools, 10-20 minutes under proot), or as CI does
# (.github/workflows/adreno-apk.yml), in an Arch Linux ARM chroot.
set -euo pipefail

# Pinned: the SHA256 is the one Arch's mesa PKGBUILD lists for the same tarball. Keep the version
# at or below Arch's mesa, since OpenGL (zink) comes from Arch's mesa package.
MESA=26.2.4
MESA_SHA256=bce5f7fbebb934373b86c999a064d52fb5065878dc57f287f95346648ec832e9

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/gpu/libvulkan_freedreno.so.xz"
WORK="${WORK:-$(mktemp -d)}"

[ "$(uname -m)" = aarch64 ] || { echo "build this on aarch64 Arch Linux ARM" >&2; exit 1; }
pacman -S --needed --noconfirm base-devel meson ninja python-mako python-yaml python-packaging \
  glslang wayland wayland-protocols zstd expat libdrm

cd "$WORK"
[ -f mesa-$MESA.tar.xz ] || curl -fL --retry 3 -o mesa-$MESA.tar.xz https://archive.mesa3d.org/mesa-$MESA.tar.xz
echo "$MESA_SHA256  mesa-$MESA.tar.xz" | sha256sum -c -
[ -d mesa-$MESA ] || tar xf mesa-$MESA.tar.xz
cd mesa-$MESA

# Only Turnip on KGSL, presenting to Wayland. No SPIRV-Tools: Turnip only uses it to dump shaders,
# and Arch's libSPIRV-Tools.so has no soname version, so an update could break a linked driver.
[ -d build ] || meson setup build --prefix=/usr/local --libdir=lib64 --buildtype=release -Db_ndebug=true \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers= -Dplatforms=wayland \
  -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false -Dgles1=disabled -Dgles2=disabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=enabled -Dspirv-tools=disabled \
  -Dvulkan-layers= -Dtools=

# Under proot, ninja's waitpid sometimes fails with ENOSYS; a rerun picks up where it stopped.
for try in 1 2 3 4 5; do
  ninja -C build -j4 && break
  [ $try -lt 5 ] || exit 1
  echo "ninja failed, retrying ($try)" >&2
done

so=build/src/freedreno/vulkan/libvulkan_freedreno.so
! ldd $so | grep -q 'not found'
strip -o "$WORK/libvulkan_freedreno.so" $so
mkdir -p "$(dirname "$OUT")"
xz -T0 -9 -c "$WORK/libvulkan_freedreno.so" > "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)); uncompressed sha256:"
sha256sum "$WORK/libvulkan_freedreno.so"
