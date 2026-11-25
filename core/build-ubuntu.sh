#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
build_dir="$repo_root/out/build/ubuntu-core"

if [[ ! -f "$repo_root/third_party/libtiff/upstream/libtiff/tiffio.h" ]]; then
  git -C "$repo_root" submodule update --init -- third_party/libtiff/upstream
fi

cmake -S "$repo_root" -B "$build_dir" -G Ninja -DCMAKE_BUILD_TYPE=Debug \
  -DPROOFTRAY_BUILD_TESTS=ON -DPROOFTRAY_BUILD_TIFF_TESTS=ON
cmake --build "$build_dir" --parallel
