# Test completeness design

**Date:** 2026-07-13  
**Status:** Design approved during brainstorming; implementation not started  
**Scope:** Local tests and developer-facing verification only. CI workflow changes are explicitly out of scope.

## Context

The project currently has unit, parser/generator, Objective-C and example smoke tests. The baseline has been verified with:

- `./gradlew cleanTest test --no-daemon`: 210 tests, 0 failures, 0 errors and 0 skipped;
- `./gradlew verifyExamples --no-daemon`: the three existing examples pass on macOS.

The suite is healthy but does not yet provide a systematic check for the CLI command layer, several internal pipeline components, compilation of generated Kotlin, Win32/DLL generation, or measured code coverage.

## Goals

1. Increase confidence in the complete extraction-to-binding workflow.
2. Test the public CLI contract, including invalid configurations and generated output.
3. Add focused tests for pipeline components that are currently covered only indirectly.
4. Compile representative generated Kotlin sources with `kotlinc`.
5. Protect representative generated outputs with stable golden files.
6. Cover Win32/DLL mapping generation without depending on system DLLs.
7. Attempt local coverage measurement without changing the Kotlin or Gradle versions.

## Non-goals

- No production behavior change is required by this design.
- No mandatory CI workflow change is included.
- No system DLL such as `kernel32.dll` or `user32.dll` is used as a test dependency.
- No Kotlin or Gradle upgrade is allowed solely to enable coverage.
- No automatic fallback to another coverage tool is introduced if the selected tool is incompatible.

## Design principles

- Prefer the smallest test level that can prove a behavior.
- Use real libclang parsing for parser behavior; avoid mocks for native cursor interactions.
- Keep test fixtures deterministic and platform-neutral whenever possible.
- Keep generated-source compilation separate from the ordinary unit-test task so a missing `kotlinc` does not hide failures in the core test suite.
- Make expected output changes explicit and reviewable.
- Each phase must leave the repository in a runnable, verifiable state.

## Test support architecture

Add reusable helpers under `src/test/kotlin/org/graphiks/kextract/testsupport/`:

- temporary C/Objective-C header creation and cleanup;
- parsing through `KextractTool`;
- the common `NameMangler` → `KotlinGenerator` flow;
- writing and sorting generated `KotlinSourceFile` instances;
- locating `kotlinc` through `KOTLINC` or `PATH`;
- compiling generated sources in a temporary directory while preserving diagnostics on failure.

The helpers must remain test-only and must not be added to the production artifact.

## Phased implementation

### Phase 1 — CLI and configuration tests

Add `KextractCommandTest` and focused configuration tests.

Cover:

- defaults and output/package options;
- library options and clang pass-through options;
- include filters and `--dump-includes`;
- `--split-output`, `--init-method` and `--variadic-args`;
- validation that `--win32` requires `--dll-map`;
- validation that `--dll-map` requires `--win32`;
- malformed YAML and malformed variadic arguments;
- effective output files and relevant exit/error behavior.

Use direct command tests for option semantics and a small process-level test for the real `kextract` launcher. All outputs must use temporary directories.

Add tests for `DllEntry` and `DllMap` YAML deserialization, including valid, empty and malformed mappings.

### Phase 2 — Internal pipeline tests

Add unit tests for model-driven components:

- `IncludeHelper` and `IncludeFilter` for every supported symbol kind, dependencies and exclusions;
- `DuplicateFilter` for duplicate and canonical declarations;
- `UnsupportedFilter` for filtered declarations and logger warnings;
- `MissingDepChecker` for missing and valid dependencies;
- `TypeMaker` for pointers, arrays, typedefs, qualified types, functions and variadics.

Add small-header integration tests for libclang-backed behavior:

- `TreeMaker` for structs, unions, enums, bitfields, functions and constants;
- `MacroParserImpl` for numeric, hexadecimal, composed and function-like macros;
- anonymous and nested declarations;
- diagnostics and source positions.

Tests should assert both retained and removed declarations, along with expected errors and warnings.

### Phase 3 — Compilation of generated bindings

Add `GeneratedBindingsCompilationTest` and a dedicated local Gradle task:

```text
./gradlew verifyGeneratedBindings
```

The task generates and compiles bindings with `kotlinc`; it does not require native execution.

C scenarios must include functions, variadics, structs, unions, enums, bitfields, pointers, arrays, typedefs, constants, split output and deferred initialization.

macOS-only Objective-C scenarios must include classes, inheritance, protocols, optional methods, properties, categories, Foundation strings, enums and options.

Compilation must use the configured JDK and preserve the complete compiler diagnostics. If `kotlinc` is absent, the dedicated task fails with an actionable message; ordinary unit tests remain runnable.

### Phase 4 — Golden files

Add deterministic fixtures under `src/test/resources/golden/`.

Each case may contain:

- input headers;
- optional YAML configuration;
- expected generated Kotlin files;
- a small manifest describing the generation mode.

Add `GoldenFileTest` that generates outputs, sorts files by path, normalizes line endings and trailing whitespace, and compares each result with its reference. Failures must show a readable diff.

Golden cases must cover:

- C structs, unions, enums, typedefs, macros, functions, arrays and variadics;
- Objective-C classes, inheritance, protocols, categories, properties and Foundation types;
- split output, deferred initialization and DLL mapping.

Reference updates must require the explicit developer-only task `updateGoldenFiles`:

```text
./gradlew updateGoldenFiles
```

The normal `test` task must never rewrite golden files automatically. Platform-specific outputs use separate fixtures rather than hidden normalization that could mask behavior.

### Phase 5 — Win32 and DLL mapping

Add `Win32GenerationTest` using a temporary C header and an in-memory `DllMap`.

Verify:

- one lookup per configured DLL;
- correct function and constant to DLL association;
- fallback to `SymbolLookup.loaderLookup()`;
- valid generated Kotlin names for varied DLL names;
- no cross-DLL symbol contamination;
- deferred initialization behavior;
- unchanged behavior in ordinary library mode.

`DllEntry.structs` must have an explicit contract: structs do not require native symbol lookup and must not be added to the function/constant lookup table merely because they appear in the mapping.

The generated code must also pass the Phase 3 compilation checks. Native execution against a real Windows DLL is not required by this phase and must not depend on system DLL availability.

### Phase 6 — Optional coverage

Attempt to add a Kotlin-compatible coverage tool, preferably Kover, using the current Kotlin and Gradle versions.

Acceptance criteria:

- the plugin resolves with the existing toolchain;
- the project compiles and tests run normally;
- an HTML report is generated;
- generated `clang/libclang/Index_h.kt` code is excluded;
- test helpers are excluded;
- coverage verification can run locally.

The first report establishes the baseline. A threshold is then selected from the measured baseline and used to prevent regressions before being increased progressively.

Compatibility is a hard gate. If the coverage tool is incompatible, unstable, or produces unreliable reports with the current Kotlin version, this phase is dropped entirely. No Kotlin or Gradle upgrade, workaround, or automatic JaCoCo fallback is permitted without a new design decision. All other phases remain valid and independent.

## Local commands

The implementation should provide clear local entry points:

```text
./gradlew test
./gradlew verifyGeneratedBindings
./gradlew verifyExamples
./gradlew updateGoldenFiles
```

`updateGoldenFiles` must be excluded from normal verification and must only update declared golden fixtures.

## Error handling and diagnostics

- Temporary directories are cleaned after successful tests.
- On generation or compilation failure, relevant generated files are preserved or copied into the Gradle build reports before cleanup.
- Process-level helpers must capture stdout and stderr and include the exit code in assertion failures.
- Missing optional tools must produce a direct remediation message.
- Tests must avoid swallowing failures through broad assumptions except for platform-specific Objective-C behavior.

## Completion criteria

The work is complete when:

1. Phases 1–5 are implemented and independently executable.
2. Existing tests and examples remain passing.
3. Generated Kotlin compilation is exercised for representative C and Objective-C cases.
4. Golden files cover the documented representative contracts.
5. Win32/DLL generation is validated without system DLL dependencies.
6. Phase 6 is either successfully enabled with a trustworthy report or explicitly removed because of Kotlin compatibility.
7. No production behavior or Kotlin version was changed solely to support the test work.
