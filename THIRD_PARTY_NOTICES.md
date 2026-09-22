# Third-party notices

## Demucs / demucs.cpp
The separation engine is based on the MIT-licensed Demucs project and the MIT-licensed demucs.cpp C++ inference port.

## libnyquist
Audio decoding/encoding uses libnyquist, released under the simplified 2-clause BSD license.

## Model
The default model is HT-Demucs 4-source GGML FP16. The build workflow downloads a pinned file and verifies SHA-256 before packaging.

Model SHA-256:
72b17c42d308982ddb5069bc3bf48b81a5aac4cb6516e4366c0fa7cef6df0064

Model source:
https://huggingface.co/ogbabydiesal/demucs-ggml
