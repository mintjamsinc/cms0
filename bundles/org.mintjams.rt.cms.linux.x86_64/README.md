---

# Building JNI + V8 (Linux, Java server)

> **TL;DR**
>
> * V8は**共有ビルド**（`is_component_build=true`）を使い、JNI側は**動的リンク+rpath(\$ORIGIN)**。
> * `libnativeecma.so` と V8の依存 `.so` を**同じフォルダに配置** → `System.load()` 一発。
> * 将来の変化に備えて**コミットIDを固定**し、**GN引数と依存リストを記録**する。

## 0. Prerequisites

* Linux x64（Clang/LLD推奨）
* Java (JDK), `javac -h` が使えること
* depot\_tools（`fetch`, `gclient`）
* ディスク数十GB・RAM 16GB以上推奨（少ない場合は `-j 1` ＋スワップ）

## 1. Fetch V8

```bash
fetch v8
cd v8
gclient sync
```

## 2. Build V8 (shared/.so)

**Why**: monolith(.a)をJNIの.soへリンクするとTLS再配置エラーになるため、**共有ビルド**が安定。

```bash
gn clean out/shared
gn gen out/shared --args='
  is_debug=false
  target_cpu="x64"
  is_component_build=true        # 共有ライブラリ
  v8_use_external_startup_data=false
  v8_enable_i18n_support=false
  v8_enable_webassembly=false
  use_custom_libcxx=true         # 付属libc++を使用（互換性◎）
  symbol_level=0
'
autoninja -C out/shared d8       # 依存含めビルド、または: autoninja -C out/shared v8 v8_libplatform
```

**生成物例**

```
out/shared/
  libv8.so
  libv8_libplatform.so
  libv8_libbase.so
  libthird_party_abseil-cpp_absl.so
  libchrome_zlib.so
  libc++.so          # use_custom_libcxx=true の場合
```

> 使った引数は `gn args out/shared --list > out/shared/args.used.txt` として**保存**しておくと再現性が上がります。
> さらに `git rev-parse HEAD > V8_COMMIT.txt` で**コミットID固定**も推奨。

## 3. JNI header (再生成)

> org.mintjams.rt.cms.linux.x86_64で実行
> srcフォルダにヘッダファイルが作成されるので、native_srcにコピーする

```bash
# 例: NativeEcma.java に native メソッドを定義済み
javac -h ./src \
  -cp <必要なクラスパス> \
  ../org.mintjams.rt.cms/src/org/mintjams/rt/cms/internal/script/engine/nativeecma/NativeEcma.java
```

> 必要になるクラスパスは以下を参照

- org.mintjams.rt.cms/bin
- org.mintjams.rt.cms/bin
- org.mintjams.tools/bin
- felix.jar
- org.osgi.service.log_1.5.0.jar
- org.osgi.service.component.annotations_1.5.0.jar

## 4. Build libnativeecma.so（rpath=\$ORIGIN）

```bash
TOOLCHAIN=~/v8/third_party/llvm-build/Release+Asserts/bin
$TOOLCHAIN/clang++ -std=c++20 -fPIC \
  -I~/v8/include \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I./native_src \
  -shared native_src/org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.cpp \
  -L~/v8/out/shared \
  -lv8 -lv8_libplatform -lv8_libbase \
  -pthread -ldl -fuse-ld=lld \
  -Wl,-rpath,'$ORIGIN' -Wl,-z,origin \
  -o libnativeecma.so
```

> `readelf -d libnativeecma.so | grep -E 'RPATH|RUNPATH'` で `$ORIGIN` が入っていることを確認。

## 5. 配置 & 実行（Java）

**同じ一時フォルダ**に以下を展開：

```
libnativeecma.so
libv8.so
libv8_libplatform.so
libv8_libbase.so
libthird_party_abseil-cpp_absl.so
libchrome_zlib.so
libc++.so                 # lddで必要なら
```

Java側：

```java
System.load(tempDir.resolve("libnativeecma.so").toString()); // これだけでOK
```

## 6. JNI 実装の初期化/終了（今回のビルドフラグ前提）

```cpp
// init
platform = v8::platform::NewDefaultPlatform();
v8::V8::InitializePlatform(platform.get());
v8::V8::Initialize();

// shutdown
v8::V8::Dispose();
v8::V8::DisposePlatform();   // ※ 環境により ShutdownPlatform の場合あり
platform.reset();
```

※ `v8_use_external_startup_data=false` / `v8_enable_i18n_support=false` なので、
`InitializeICUDefaultLocation` / `InitializeExternalStartupData` は**不要**。

## 7. 推奨ランタイム設計（高速＆クリーン）

* **Isolateプール + Context使い捨て**

  * 並列 = Isolate数、各Isolateは専用スレッドで実行
  * 各 `eval` で **新規Context作成 → 実行 → 破棄**（クリーン保証）
  * `isolate->SetMicrotasksPolicy(kAuto)` + `PerformMicrotaskCheckpoint()`
  * （任意）`TerminateExecution()`で**タイムアウト**、`AddNearHeapLimitCallback`で**メモリ上限**

## 8. トラブルシューティング（頻出）

* **ビルドが落ちる（OOM）**: `autoninja -j 1`、スワップ追加（16〜32GB）
* **`std::bit_cast`系エラー**: `use_custom_libcxx=true` で再生成
* **.eh\_frame/CRELでリンク失敗**: LLD を使用（`-fuse-ld=lld`）
* **TLS再配置エラー（R\_X86\_64\_TPOFF32）**: monolith(.a)を.soに入れない → 共有ビルドに切替
* **依存 .so が見つからない**: `$ORIGIN` rpath を確認、依存 `.so` 一式を同フォルダへ
* **Javaで `UnsatisfiedLinkError`**: `ldd libnativeecma.so`/`libv8.so`で“not found”を確認→不足 `.so` を追加

## 9. Windows（参考）

* すべての DLL を同じフォルダに置き、`System.load("...\\nativeecma.dll")`。
* 必要なら依存DLL（`v8.dll` 等）を先に `System.load`。

## 10. 将来の変化に強くするコツ

* **コミットID固定**（`V8_COMMIT.txt` に記録）
* **引数保存**（`out/shared/args.used.txt`）
* `ldd libv8.so` の**依存一覧をREADMEに残す**（次回差分検知用）
* 重要コマンドを**スクリプト化**（`scripts/build_v8_shared.sh`, `scripts/link_jni.sh`）

---
