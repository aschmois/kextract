# KMP JVM native library bootstrap design

## Problem

`kextract --multiplatform` accepts `--library`, but the KMP pipeline currently
does not pass those libraries to `KotlinKmpJvmBuilder`. Generated JVM bindings
therefore call `io.ygdrasil.kffi.findOrThrow` before the requested native
library has necessarily been loaded.

A module-specific resolver hook would fix that symptom, but would leave every
generated module responsible for hand-writing the same bootstrap. A generic
bootstrap must also account for bundled dependencies and auxiliary files:
extracting only the entry library to an arbitrary temporary file is not a
portable guarantee on Linux, macOS, and Windows.

## Goals

- Make existing ordered `--library` arguments effective for KMP/JVM output.
- Generate a self-contained JVM bootstrap with no module-specific resolver or
  loader dependency.
- Discover conventionally packaged JVM native resources at generation time.
- Extract a complete platform bundle while preserving relative paths.
- Address cache/version collisions by resource content hashes rather than a
  semantic version string.
- Load declared native libraries in CLI order before the first dependent symbol
  lookup.
- Keep loading idempotent, thread-safe, and retryable after failure.
- Preserve the original load failure.
- Add no loader operation to an already initialized generated downcall.
- Validate actual loading locally on macOS, in a Linux Docker container, and in
  CI on Linux, macOS, and Windows.

## Non-goals

- Do not infer native dependency graphs by parsing ELF, Mach-O, or PE binaries.
- Do not change Android or Kotlin/Native generation.
- Do not change callback generation or callback lifecycle.
- Do not mutate global process search paths such as `PATH`,
  `LD_LIBRARY_PATH`, `DYLD_LIBRARY_PATH`, or the Windows DLL directory list.
- Do not guarantee libraries which open arbitrary data files relative to the
  process working directory. Bundled auxiliary files must be addressed relative
  to the loaded module or through the library's own API.

## Resource convention

For a named option such as `--library wgpu_native`, the platform entry filename
is the platform mapping of that logical name:

| Platform key | Entry filename |
| --- | --- |
| `darwin-aarch64` | `libwgpu_native.dylib` |
| `darwin-x86-64` | `libwgpu_native.dylib` |
| `linux-aarch64` | `libwgpu_native.so` |
| `linux-x86-64` | `libwgpu_native.so` |
| `win32-x86-64` | `wgpu_native.dll` |

The conventional generation-time resource root is
`<output>/jvmMain/resources`. Each recognized platform directory is a complete
bundle. `kextract` recursively indexes every regular file in these directories,
including native dependencies and auxiliary files, and embeds the relative
paths plus SHA-256 hashes in the generated JVM source.

All non-system native dependencies that require explicit preloading must also
be supplied as preceding `--library` arguments. The CLI order is the load order.
This avoids unreliable dependency guessing while requiring no separate
module-specific manifest.

If the conventional resource root or the entry resource for a named library is
absent, generated code falls back to `System.loadLibrary(logicalName)`. A
path-form option such as `--library :/opt/lib/libsample.so` is loaded through
`System.load` using its normalized absolute path and does not participate in
resource extraction.

## Generation architecture

`Options.libraries` is passed through `KotlinGenerator.generateKmp` into
`KotlinKmpJvmBuilder`. A small build-time resource index scans only the known
JVM resource root and produces immutable platform bundle descriptors.

When at least one library is present, `KotlinKmpJvmBuilder` emits collision-safe
private helper names through the existing KMP name plan. Generated address
initializers keep their existing lazy shape but resolve through a generated
function which first completes the library bootstrap and then delegates to the
existing kffi lookup.

When no library is supplied, generated output remains byte-for-byte equivalent
for imports and symbol lookup behavior.

## Runtime extraction and loading

For the current OS and architecture, generated code:

1. Selects the embedded platform bundle descriptor.
2. Derives an aggregate bundle key from the embedded per-file SHA-256 hashes.
3. Uses `${java.io.tmpdir}/kextract-native/<bundle-key>` by default, overridable
   with the `kextract.native.cache.dir` system property.
4. Acquires a cross-process file lock in the bundle directory.
5. Validates existing files against their embedded hashes.
6. Copies missing or invalid files to sibling temporary files and atomically
   replaces their final paths, preserving the bundle hierarchy.
7. Releases the extraction lock.
8. Loads declared resource-backed libraries by absolute path in CLI order.
9. Falls back to `System.loadLibrary` only for named entries absent from the
   indexed platform bundle.

The main process never changes its working directory or global native search
path. Bundled Linux dependencies must use `$ORIGIN`-relative linkage and bundled
macOS dependencies must use `@loader_path` or a compatible `@rpath`. Windows
dependencies should be explicitly listed before their consumers so they are
already loaded by absolute path.

## State and failure semantics

The generated loader uses a volatile loaded flag with a synchronized double
check. It sets the flag only after every declared library has loaded
successfully. Exceptions are not wrapped.

After failure the flag remains false. The next caller, including a concurrent
caller already waiting for the monitor, retries validation/extraction and the
complete ordered load. Files successfully extracted before the failure remain
eligible for hash validation and reuse.

The generated symbol address remains a Kotlin `lazy`. A failed initializer can
therefore retry on a later invocation without poisoning JVM class
initialization.

## Performance

The first native call pays resource validation, extraction when required,
dynamic linking, and the first symbol lookup. An already extracted bundle is
validated by hashes before load; no extraction is repeated.

Each different generated symbol first initialized after the library is loaded
performs one volatile fast-path check. Calls through an already initialized
generated method handle do not enter the generated resolver or loader and add
no new steady-state operation.

## Tests

### Generator RED/GREEN tests

- A KMP generation with `--library fixture_dependency --library fixture_main`
  must emit a resource index, ordered absolute-path loads, and bootstrap-before-
  lookup.
- No-library KMP generation must retain the direct kffi lookup.
- Resource paths and helper identifiers must remain collision-safe.
- Missing resource entries must generate `System.loadLibrary` fallback.
- Path-form libraries must generate absolute `System.load` behavior.

### Actual host loader fixture

Build two tiny native libraries on the host:

- `fixture_dependency` exports an integer-returning function;
- `fixture_main` links against it and exports a function which calls it.

Package both in the host platform resource directory, declare dependency before
main, generate bindings, compile the generated JVM source, and launch a fresh
child JVM. Its first generated downcall must return the expected value without
an explicit bootstrap.

Additional child-process modes cover concurrent first access, retry after an
initially unavailable bundle, a cache path containing spaces, and exactly one
effective load sequence.

### Platform validation

- macOS local: run focused generator and loader tests on the current arm64 host.
- Linux local: run the same focused tests in an amd64 Linux Docker container.
- GitHub Actions: run the complete test suite and native bundle fixture on the
  existing `ubuntu-latest`, `macos-14`, and `windows-latest` matrix.

The CI fixture build must use `$ORIGIN` on Linux, `@loader_path` on macOS, and
explicit dependency-first loading on Windows. The test fails if a dependent
library is accidentally found through a machine-global search path.

## wgpu4k-native integration

After the kextract implementation is available:

- rename JVM resources from `libWGPU.*`/`WGPU.dll` to the platform mapping of
  the already declared logical name `wgpu_native`;
- regenerate `wgpu_hJvm.kt` with `--library wgpu_native`;
- remove the hand-written JVM `LibraryLoader.kt` and Gradle import rewrite;
- retain a read-only generation verification task;
- retain isolated first-downcall, callback, and concurrent consumer tests;
- validate the standalone Maven Local consumer again.

The kextract change is developed and reviewed in its own branch and pull
request. The wgpu4k-native pull request is updated only after the kextract
commit is pushed and available to the submodule.
