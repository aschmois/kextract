# KMP/JVM Native Bootstrap Implementation Plan

> **For Codex:** execute this plan with `superpowers:executing-plans`, preserving strict RED/GREEN evidence and avoiding all Android files and tasks.

**Goal:** Make generated KMP/JVM bindings automatically load their declared native libraries before their first symbol lookup, using bundled platform resources when present and the system loader otherwise.

**Architecture:** At generation time, kextract indexes standard platform resource directories below `jvmMain/resources`, including relative paths and SHA-256 digests. The JVM generator emits a module-private, collision-safe bootstrap that selects the current platform, extracts the selected bundle into a content-addressed cache under a cross-process lock, loads declared libraries in CLI order, and only then delegates to kffi `findOrThrow`. A synchronized retryable state machine makes first access idempotent and thread-safe; no-library output remains unchanged.

**Tech Stack:** Kotlin/JVM, KotlinPoet, Gradle, Java FFM API, JUnit 5, host C toolchains, GitHub Actions.

---

## Task 1: Build-time native bundle index

**Files:**
- Create: `src/main/kotlin/org/graphiks/kextract/kotlin/KotlinJvmNativeBundleIndex.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/kotlin/KotlinJvmNativeBundleIndexTest.kt`

1. Add RED tests for recursive indexing of `darwin-aarch64`, `darwin-x86-64`, `linux-aarch64`, `linux-x86-64`, and `win32-x86-64`; deterministic ordering; SHA-256 metadata; platform-specific library filename mapping; missing entries; and absolute `:/path` declarations.
2. Run only the new test and capture the compilation/test failure.
3. Implement the immutable index model and scanner with no host-platform assumptions.
4. Rerun the test and capture GREEN.

## Task 2: Propagate libraries and the resource index into KMP/JVM generation

**Files:**
- Modify: `src/main/kotlin/org/graphiks/kextract/pipeline/KextractTool.kt`
- Modify: `src/main/kotlin/org/graphiks/kextract/kotlin/KotlinGenerator.kt`
- Modify: `src/main/kotlin/org/graphiks/kextract/kotlin/builders/KotlinKmpJvmBuilder.kt`
- Modify: `src/test/kotlin/org/graphiks/kextract/integration/GeneratorIntegrationTest.kt`
- Modify: `src/test/kotlin/org/graphiks/kextract/impl/KextractToolTest.kt`

1. Add a RED generation test proving `--library` is currently ignored by KMP/JVM and requiring a generated resolver that performs bootstrap before kffi lookup.
2. Run the focused test and record RED.
3. Scan `<output>/jvmMain/resources` once in `KextractTool`, pass declared libraries and the optional index through `KotlinGenerator`, and select the bootstrap resolver only when libraries are declared.
4. Preserve byte-for-byte behavior for KMP/JVM output with no declared library.
5. Rerun focused generator tests and record GREEN.

## Task 3: Emit the retryable, collision-safe JVM bootstrap

**Files:**
- Create: `src/main/kotlin/org/graphiks/kextract/kotlin/builders/KotlinJvmNativeBootstrapEmitter.kt`
- Modify: `src/main/kotlin/org/graphiks/kextract/kotlin/KotlinKmpNamePlan.kt`
- Modify: `src/main/kotlin/org/graphiks/kextract/kotlin/builders/KotlinKmpJvmBuilder.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/kotlin/builders/KotlinJvmNativeBootstrapEmitterTest.kt`

1. Add RED source-generation tests for deterministic load order, bundled resource metadata, `System.loadLibrary` fallback, absolute `System.load`, cache override property, path preservation, hash validation, cross-process locking, atomic replacement fallback, synchronized concurrent initialization, loaded-after-success semantics, cause preservation, and retry after failure.
2. Add collision cases where headers declare names matching every generated helper.
3. Run focused tests and record RED.
4. Implement a focused emitter with an allocated private namespace. Emit:
   - a volatile/synchronized load controller;
   - platform detection;
   - content-addressed cache selection;
   - lock-file acquisition;
   - recursive resource extraction with digest validation;
   - temporary sibling writes plus atomic move and safe fallback;
   - ordered `System.load`/`System.loadLibrary` calls;
   - a generated resolver that calls load first and kffi `findOrThrow` second.
5. Ensure failure never flips the loaded flag and the original throwable escapes unchanged, allowing the next call to retry.
6. Rerun focused tests and record GREEN.

## Task 4: Exercise real native libraries in isolated JVMs

**Files:**
- Create: `src/test/native-bootstrap-fixture/dependency.c`
- Create: `src/test/native-bootstrap-fixture/main.c`
- Create: `src/test/kotlin/org/graphiks/kextract/integration/KmpJvmNativeBootstrapIntegrationTest.kt`
- Modify: `build.gradle.kts`

1. Add a RED integration test that generates and compiles a minimal KMP/JVM binding, then launches a fresh child JVM with no explicit loader.
2. Build a two-library fixture for the host OS. Link the main library to the dependency using `$ORIGIN` on Linux, `@loader_path` on macOS, and dependency-first explicit loading on Windows.
3. Cover in isolated JVMs:
   - first native downcall;
   - callback creation/registration/close before explicit bootstrap;
   - simultaneous first calls from multiple threads;
   - a cache directory containing spaces;
   - exact-once effective loading;
   - injected first-load failure followed by a successful retry with original cause preservation.
4. Run the focused integration test and capture RED, then GREEN.

## Task 5: Documentation and cross-platform CI

**Files:**
- Modify: `README.md`
- Modify: `.github/workflows/test.yml`

1. Document the standard resource directory names, platform filename mapping, ordered dependency convention, `:/absolute/path`, `System.loadLibrary` fallback, cache location/override, retry behavior, and linker requirements.
2. Ensure the existing Linux/macOS/Windows matrix installs or activates the native compiler needed by the fixture (MSVC developer environment on Windows).
3. Run all non-Android kextract tests and `git diff --check`.

## Task 6: Validate locally and publish the kextract PR

1. Run the full JVM test suite on macOS.
2. Run the native-bootstrap and generator suites in a Linux Docker container, mounting a Gradle cache outside the source tree where practical.
3. Review the diff for generated API leakage, platform assumptions, callback changes, and Android changes.
4. Commit on `fix/kmp-jvm-native-bootstrap`, push, and open a draft PR against `klang-toolkit/kextract:master`.
5. Wait for and inspect the GitHub Actions Linux/macOS/Windows matrix; fix any failure and revalidate until green.

## Task 7: Consume the generator in wgpu4k-native

**Files:**
- Modify: `wgpu4k-native-generator/build.gradle.kts`
- Modify: `wgpu4k-native-jvm/src/main/kotlin/io/ygdrasil/wgpu/wgpu_h.kt`
- Modify/delete obsolete hand-written JVM bootstrap files and tests introduced by the first fix
- Update: `kextract` submodule pointer

1. Rename copied JVM resources to the platform mapping of `--library wgpu_native` while preserving any sibling dependencies.
2. Regenerate the JVM binding with the updated kextract generator.
3. Remove the hand-written loader and generated-source rewrite; retain a build assertion that generated downcalls route through the bootstrap.
4. Run isolated first-downcall, callback, concurrency, failure/retry, and no-legacy-loader tests.
5. Publish the corrected artifact to Maven Local and compile/run a minimal external JVM consumer that imports no `ffi.*` API.
6. Run relevant non-Android JVM tests and Kotlin/Native compilations, then `git diff --check`.
7. Commit and push `fix/jvm-native-bootstrap`, update PR #6, and wait for its three-OS CI matrix.
