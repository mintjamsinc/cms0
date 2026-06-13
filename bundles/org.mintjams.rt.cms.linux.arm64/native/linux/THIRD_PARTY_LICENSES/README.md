# Third-Party License Texts for Bundled Native Libraries

This directory contains the verbatim upstream license texts for the
third-party native shared objects shipped under `../`. The files are
included to satisfy the attribution requirements of the BSD 3-Clause,
Apache License 2.0, Apache License 2.0 with LLVM Exceptions, and zlib
licenses for binary redistribution.

| License file | Covers |
| --- | --- |
| `V8_LICENSE.txt` | `libv8.so`, `libv8_libplatform.so`, `libv8_libbase.so` ([Google V8](https://v8.dev/), BSD 3-Clause). This is the V8 source tree's top-level `LICENSE`; it enumerates the externally-maintained sub-components below and states the BSD 3-Clause terms for everything else. |
| `V8_LICENSE.v8.txt` | The BSD 3-Clause grant for the V8 project itself (V8 source tree's `LICENSE.v8`). |
| `V8_LICENSE.fdlibm.txt` | fdlibm math library code embedded in V8 (V8 source tree's `LICENSE.fdlibm`, Sun Microsystems freely-granted license). |
| `V8_LICENSE.strongtalk.txt` | Strongtalk assembler code, the basis of V8's `assembler-*` files (V8 source tree's `LICENSE.strongtalk`, BSD 3-Clause / Sun Microsystems). |
| `ABSEIL_LICENSE.txt` | `libthird_party_abseil-cpp_absl.so` ([Abseil C++](https://abseil.io/), Apache License 2.0). |
| `ZLIB_LICENSE.txt` | `libchrome_zlib.so` ([zlib](https://zlib.net/), zlib License). |
| `LIBCXX_LICENSE.txt` | `libc++.so` — the [LLVM](https://llvm.org/) project's libc++ C++ standard library (and the libc++abi it embeds), Apache License 2.0 with LLVM Exceptions. This is the LLVM source tree's `LICENSE.TXT`. It is shipped because V8 is built with `use_custom_libcxx=true`, which emits libc++ as a separate `.so` that the V8 libraries link against. |

`libnativeecma.so` is built from this project's own C++ sources (see
`../../../native_src/`) and is governed by the repository's top-level
`LICENSE`.

For the full third-party inventory of this bundle, see
[`../../../../THIRD_PARTY_LICENSES.md`](../../../../THIRD_PARTY_LICENSES.md).
