#!/usr/bin/env bash
set -euo pipefail
git submodule sync --recursive
git submodule update --init --recursive
mkdir -p app/src/main/assets
curl -L --fail --retry 3 "https://huggingface.co/ogbabydiesal/demucs-ggml/resolve/main/ggml-htdemucs-4s-f16.bin?download=true"   -o app/src/main/assets/ggml-htdemucs-4s-f16.bin
echo "72b17c42d308982ddb5069bc3bf48b81a5aac4cb6516e4366c0fa7cef6df0064  app/src/main/assets/ggml-htdemucs-4s-f16.bin" | sha256sum -c -
echo "Dependencies and model are ready."
