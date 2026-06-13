# Native ECMA engine — build scripts

Scripts to (re)build `libnativeecma.so` and its V8 runtime dependencies for the
`org.mintjams.rt.cms.linux.x86_64` bundle. See the bundle [`../README.md`](../README.md)
for the underlying rationale (V8 shared build, `$ORIGIN` rpath, platform-once rule).

> ⚠️ **Platform-once rule.** The V8 platform is initialized exactly once per
> process and is **never** disposed (see `nativeInitPlatform` / `std::call_once`
> in the native source). Do not reintroduce `Dispose`/`DisposePlatform` on
> workspace teardown — it aborts the JVM with
> `Check failed: current_state != V8StartupState::kPlatformDisposed`.

## Scripts

| Script | Purpose | When to run |
|---|---|---|
| `build_v8_shared.sh` | Build V8 as a shared/component build (`.so`). | Once, or when bumping the V8 revision. Heavy (tens of GB, 16GB+ RAM). |
| `gen_jni_header.sh` | Regenerate the JNI header via `javac -h`. | Only when native method signatures in `NativeEcma.java` change. |
| `build_nativeecma.sh` | Compile + link `libnativeecma.so` against V8. | Every native source change. |
| `deploy_natives.sh` | Copy the `.so` set into `native/linux/x86_64/`. | After `build_nativeecma.sh`. |
| `build_all.sh` | Run header → build → deploy (optionally V8 too). | Convenience wrapper. |

## Typical usage

First time (build V8, then everything):

```bash
export JAVA_HOME=/path/to/jdk-17
./build_all.sh --with-v8
```

Day-to-day, after editing the native source (V8 already built):

```bash
export JAVA_HOME=/path/to/jdk-17
./build_all.sh                 # header + build + deploy
# or, if no Java toolchain handy and signatures are unchanged:
./build_all.sh --skip-header
```

Then commit the regenerated binaries:

```bash
git add native/linux/x86_64/*.so native_src/*.h
git commit
```

## Configuration (env vars)

| Var | Default | Used by |
|---|---|---|
| `TARGET_ARCH` | `x86_64` | v8 / nativeecma / deploy (`x86_64` or `arm64`) |
| `V8_DIR` | `~/v8` | v8 / nativeecma / deploy |
| `V8_OUT` | `out/shared` (x86_64), `out/<arch>` otherwise | v8 / nativeecma / deploy |
| `SYSROOT` | auto (`$V8_DIR/build/linux/*arm64-sysroot`) | nativeecma (arm64) |
| `JAVA_HOME` | from PATH | header / nativeecma (jni.h) |
| `TOOLCHAIN` | `$V8_DIR/third_party/llvm-build/Release+Asserts/bin` | nativeecma |
| `CLASSPATH` | auto-discovered | header (`javac -h`) |
| `JOBS` | autoninja default | v8 (set `1` on OOM) |

## Cross-compiling for arm64

The native engine is built for **Linux / arm64 (aarch64)** by cross-compiling
from an x86_64 host — no arm64 hardware is needed to build, only to run/test.
V8 ships an arm64 sysroot in its checkout and its bundled clang is a
cross-compiler; the scripts wire these up when `TARGET_ARCH=arm64`.

```bash
export JAVA_HOME=/path/to/jdk-17

# 1) cross-build V8 for arm64 (installs the arm64 sysroot, out/arm64)
TARGET_ARCH=arm64 ./build_v8_shared.sh

# 2) cross-build + deploy into bundles/org.mintjams.rt.cms.linux.arm64/
TARGET_ARCH=arm64 ./build_all.sh --skip-header     # header is arch-independent
```

`build_nativeecma.sh` adds `--target=aarch64-linux-gnu --sysroot=...`, and
`deploy_natives.sh` writes the `.so` set into the **arm64 bundle**
(`org.mintjams.rt.cms.linux.arm64/native/linux/arm64/`). Because `ldd` can't
introspect a foreign-arch ELF on an x86_64 host, the cross deploy verifies
`DT_NEEDED` coverage instead; do a real load test on arm64 hardware or under
`qemu-aarch64`. Re-confirm the shipped `.so` set with `readelf -d` and update
the arm64 bundle's `THIRD_PARTY_LICENSES` (including `LIBCXX_LICENSE.txt`) the
same way as x86_64.

`gen_jni_header.sh` needs the rt.cms / tools bundle outputs and the OSGi
framework on the classpath. It auto-discovers common Eclipse/PDE locations; if
that fails, pass `CLASSPATH` explicitly:

```bash
CLASSPATH="/path/org.mintjams.rt.cms/bin:/path/org.mintjams.tools/bin:/path/felix.jar" \
  ./gen_jni_header.sh
```

## CI: build without a local toolchain

If you don't want to set up depot_tools locally, the GitHub Actions workflow
[`.github/workflows/native-ecma.yml`](../../../.github/workflows/native-ecma.yml)
runs these same scripts and **commits the rebuilt `native/linux/x86_64/*.so`
back to the branch** (with `[skip ci]`, so it doesn't loop).

* Triggers: manual (**Run workflow**) and on pushes that touch `native_src/**`
  or `NativeEcma.java`.
* The V8 SDK (headers + shared libs + bundled clang) is cached, so only the
  first run / a V8 bump pays the full V8 build cost; later runs just relink.
* Repo **Variables** (Settings → Secrets and variables → Actions → Variables):
  * `V8_COMMIT` — pin V8 to a commit/tag for reproducibility (changing it
    rebuilds the SDK cache).
  * `RUNNER_LABEL` — use a bigger/self-hosted runner if the default one is too
    small for the V8 build (default `ubuntu-latest`).
* The JNI header is **not** regenerated in CI (no OSGi classpath there). If you
  change native method signatures, run `gen_jni_header.sh` locally and commit
  the header first.
