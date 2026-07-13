# Test Completeness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the local test system so the CLI, parser pipeline, generated Kotlin, golden outputs and Win32/DLL generation are validated independently, with optional coverage only when compatible with the existing Kotlin toolchain.

**Architecture:** Keep production code unchanged and add focused test-only support under `src/test/kotlin/org/graphiks/kextract/testsupport`. Use direct model tests for filters, real libclang fixtures for parser behavior, a dedicated Gradle `Test` task for generated-source compilation, explicit golden-file update tooling, and string/compile assertions for Win32 generation without loading system DLLs.

**Tech Stack:** Kotlin 2.3.21, Gradle 9.5.1, JUnit 5, Kotest 6.1.11, libclang 22.1.6, JDK 25, `kotlinc`, optional Kover 0.9.8 only if it is compatible with the current Kotlin and Gradle versions.

## Global Constraints

- Local tests and developer-facing verification only; CI workflow changes are out of scope.
- No production behavior change is required by this work.
- No Kotlin or Gradle upgrade is allowed solely to enable coverage.
- If the coverage tool is incompatible, unstable or unreliable with the current Kotlin version, drop that phase entirely.
- No automatic JaCoCo fallback is allowed without a new design decision.
- No test may depend on `kernel32.dll`, `user32.dll` or another system DLL.
- Test helpers must stay in the test source set and must not enter the production artifact.
- Objective-C parser tests remain macOS-only; C and model tests remain platform-neutral where possible.
- Golden files are updated only through the explicit `updateGoldenFiles` task.
- Every task ends with an independently runnable test command and a focused commit.

---

## File map

### Test support files

- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/GeneratedSourceTestSupport.kt` — temporary headers, parsing, name mangling and generation.
- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/KotlinCompilerSupport.kt` — `kotlinc` discovery, temporary compilation and diagnostics.
- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/ProcessTestSupport.kt` — child JVM process execution and captured output for CLI tests.

### New test files

- Create: `src/test/kotlin/org/graphiks/kextract/cli/DllMapTest.kt` — YAML model behavior.
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/KextractCommandTest.kt` — direct CLI option validation and output behavior.
- Create: `src/test/kotlin/org/graphiks/kextract/integration/KextractCliIntegrationTest.kt` — child-process invocation of the real `KextractTool.main` entry point.
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/FilterTest.kt` — `IncludeHelper`, `IncludeFilter`, `DuplicateFilter` and `UnsupportedFilter`.
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/ParserPipelineIntegrationTest.kt` — `TreeMaker`, `TypeMaker` and libclang-backed declarations.
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/MacroParserIntegrationTest.kt` — macro parsing through real headers.
- Create: `src/test/kotlin/org/graphiks/kextract/integration/GeneratedBindingsCompilationTest.kt` — compile generated C and Objective-C Kotlin.
- Create: `src/test/kotlin/org/graphiks/kextract/integration/GoldenFileTest.kt` — compare generated sources with references and support explicit updates.
- Create: `src/test/kotlin/org/graphiks/kextract/integration/Win32GenerationTest.kt` — per-DLL lookup generation and deferred initialization.

### Fixtures and build configuration

- Create: `src/test/resources/golden/c/structs-and-functions.h` — representative C generator fixture.
- Create: `src/test/resources/golden/c/constants-and-macros.h` — constants and macro fixture.
- Create: `src/test/resources/golden/c/variadic.h` — variadic fixture.
- Create: `src/test/resources/golden/objc/classes-and-protocols.h` — Objective-C class/protocol fixture.
- Create: `src/test/resources/golden/objc/categories-and-properties.h` — Objective-C category/property fixture.
- Create: `src/test/resources/golden/win32/sample.h` — declarations used with a multi-DLL mapping.
- Create: `src/test/resources/golden/win32/sample.yml` — deterministic DLL mapping.
- Create: expected `.kt` files next to each golden fixture, generated only by `updateGoldenFiles`.
- Modify: `build.gradle.kts:1-205` — only for test task configuration and, if compatible, optional coverage.
- Modify: `build.gradle.kts:353-385` — add local verification tasks next to `verifyExamples`.

## Implementation tasks

### Task 1: Add shared test support

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/GeneratedSourceTestSupport.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/KotlinCompilerSupport.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/testsupport/ProcessTestSupport.kt`
- Test through: `src/test/kotlin/org/graphiks/kextract/integration/GeneratorIntegrationTest.kt`

**Interfaces:**

```kotlin
enum class HeaderLanguage { C, OBJECTIVE_C }

data class GenerationRequest(
    val source: String,
    val language: HeaderLanguage = HeaderLanguage.C,
    val packageName: String = "test",
    val libraries: List<Options.Library> = emptyList(),
    val splitOutput: Boolean = false,
    val variadicArgs: Map<String, Int> = emptyMap(),
    val win32Mode: Boolean = false,
    val dllMap: DllMap? = null,
    val useInitMethod: Boolean = false,
)

object GeneratedSourceTestSupport {
    fun parse(request: GenerationRequest): Declaration.Scoped
    fun generate(request: GenerationRequest): List<KotlinSourceFile>
    fun contentByPath(files: List<KotlinSourceFile>): Map<String, String>
}

data class CompilationResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object KotlinCompilerSupport {
    fun compilerPath(): Path
    fun compile(files: List<KotlinSourceFile>): CompilationResult
}
```

**Steps:**

- [ ] **Step 1: Write a failing support-level test** in `GeneratorIntegrationTest.kt` that uses `GeneratedSourceTestSupport.generate(...)` for `int add(int a, int b);` and asserts the generated map contains the header output path.
- [ ] **Step 2: Run the focused test** with `./gradlew test --tests 'org.graphiks.kextract.integration.GeneratorIntegrationTest'`; expected result is a compilation failure because the helper does not exist.
- [ ] **Step 3: Implement `GeneratedSourceTestSupport`** by following the existing flow: create a temporary `.h`, call `KextractTool.parse`, pass `-x objective-c` for `OBJECTIVE_C`, run `NameMangler`, then call `KotlinGenerator.generate` with every `GenerationRequest` option.
- [ ] **Step 4: Implement `contentByPath`** using `KotlinSourceFile.getPath()` as the stable key and `contents` as the value.
- [ ] **Step 5: Run the focused test again**; expected result is PASS with the existing parser/generator behavior unchanged.
- [ ] **Step 6: Add `KotlinCompilerSupport`**. Resolve `KOTLINC` first, then the first executable `kotlinc` on `PATH`; throw an `IllegalStateException` containing both lookup locations when absent. Write sources to a temporary directory, invoke `kotlinc -jdk-home <java.home> -d <temp>/generated.jar <all .kt files>`, and return exit code, stdout and stderr.
- [ ] **Step 7: Add `ProcessTestSupport`** with `run(command: List<String>, workingDirectory: Path, environment: Map<String, String> = emptyMap()): ProcessResult`; capture merged stdout/stderr, wait for completion and include the exit code. The CLI integration test must construct the child JVM command as `Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")`, add `-cp` with `System.getProperty("java.class.path")`, then add `org.graphiks.kextract.pipeline.KextractTool` and the CLI arguments.
- [ ] **Step 8: Run `./gradlew test`**; expected result is the existing suite plus the support consumer passing.
- [ ] **Step 9: Commit** with `git add src/test/kotlin/org/graphiks/kextract/testsupport src/test/kotlin/org/graphiks/kextract/integration/GeneratorIntegrationTest.kt && git commit -m "test: add shared generation test support"`.

### Task 2: Cover CLI options and DLL configuration

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/cli/DllMapTest.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/KextractCommandTest.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/integration/KextractCliIntegrationTest.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/cli/Configuration.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/KextractCommand.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/KextractTool.kt`

**Interfaces:**

- Direct command tests invoke `KextractCommand(logger).main(args)` and assert the generated temporary output or `ProgramResult`.
- Process tests invoke the child JVM entry point `org.graphiks.kextract.pipeline.KextractTool` through `ProcessTestSupport`; they must not call `System.exit` in the test JVM.

**Steps:**

- [ ] **Step 1: Write failing YAML tests** for a mapping containing `functions: [CreateWindow]`, `structs: [Window]` and `constants: [WindowStyle]`, an empty mapping, and malformed YAML. Assert `DllMap.dllMap`, `DllEntry.functions`, `DllEntry.structs` and `DllEntry.constants`.
- [ ] **Step 2: Run `./gradlew test --tests 'org.graphiks.kextract.cli.DllMapTest'`**; expected result is failure because the test file is not implemented.
- [ ] **Step 3: Implement the YAML tests** with `ObjectMapper(YAMLFactory())` and temporary files; do not add production deserialization code because `loadDllMap` already owns that behavior.
- [ ] **Step 4: Write failing direct CLI tests** for defaults, `--output`, `--target-package`, `--library`, `--include-function`, `--split-output`, `--init-method`, `--variadic-args`, `--win32` without `--dll-map`, `--dll-map` without `--win32`, malformed YAML and invalid variadic counts.
- [ ] **Step 5: Run the focused CLI tests**; expected result is failure because `KextractCommandTest` is not implemented.
- [ ] **Step 6: Implement direct CLI tests** with a temporary `int add(int, int);` header. Assert output files, package declaration, split file names, `init()` presence, and exact validation messages for the two Win32/DLL option mismatches.
- [ ] **Step 7: Write a failing process-level test** that runs `KextractTool` on a temporary header with `--output <temp>` and asserts exit code `0`, a generated `.kt` file and no fatal error.
- [ ] **Step 8: Add process-level error cases** for a missing header and a malformed `--variadic-args` value; assert non-zero exit and the diagnostic text without asserting platform-specific stack-trace formatting.
- [ ] **Step 9: Run `./gradlew test --tests 'org.graphiks.kextract.pipeline.KextractCommandTest' --tests 'org.graphiks.kextract.integration.KextractCliIntegrationTest'`**; expected result is PASS.
- [ ] **Step 10: Commit** with `git add src/test/kotlin/org/graphiks/kextract/cli/DllMapTest.kt src/test/kotlin/org/graphiks/kextract/pipeline/KextractCommandTest.kt src/test/kotlin/org/graphiks/kextract/integration/KextractCliIntegrationTest.kt && git commit -m "test: cover CLI and DLL configuration"`.

### Task 3: Cover filters, types, parser and macros

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/FilterTest.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/ParserPipelineIntegrationTest.kt`
- Create: `src/test/kotlin/org/graphiks/kextract/pipeline/MacroParserIntegrationTest.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/IncludeHelper.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/IncludeFilter.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/DuplicateFilter.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/UnsupportedFilter.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/TypeMaker.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/TreeMaker.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/pipeline/MacroParserImpl.kt`

**Steps:**

- [ ] **Step 1: Write failing model tests** that build `Declaration.toplevel(...)` values containing one function, variable, constant, struct, union, typedef, Objective-C class, protocol and category; configure one `IncludeHelper.IncludeKind` at a time; assert `IncludeFilter.scan(...)` retains the requested declaration and required referenced types while excluding unrelated declarations.
- [ ] **Step 2: Run `./gradlew test --tests 'org.graphiks.kextract.pipeline.FilterTest'`**; expected result is failure because `FilterTest` is absent.
- [ ] **Step 3: Implement `FilterTest`** using the existing `Declaration` factory methods and a logger capturing stderr. Add duplicate declarations with the same identity and assert `DuplicateFilter` returns one logical declaration. Add unsupported declarations and assert `UnsupportedFilter` marks them skipped and emits the expected warning.
- [ ] **Step 4: Add filter edge cases** for an empty include set, a missing dependency, a typedef referring to a selected struct, and `KextractConfig.verbose` both false and true. Assert errors from `MissingDepChecker` in both modes.
- [ ] **Step 5: Write failing parser integration tests** using inline headers for a struct with arrays and pointers, a union, an enum, bitfields, nested/anonymous declarations, a function with a callback-like type and a variadic function. Assert the parsed `Declaration` and `Type` kinds rather than generated text.
- [ ] **Step 6: Run `./gradlew test --tests 'org.graphiks.kextract.pipeline.ParserPipelineIntegrationTest'`**; expected result is failure because the new integration suite is absent.
- [ ] **Step 7: Implement parser assertions** through `KextractTool.parse(...)`, using `-x objective-c` only in macOS-gated cases. Assert source positions for a known line/column and assert Clang diagnostics are surfaced for a deliberately invalid header.
- [ ] **Step 8: Write failing macro tests** for `#define COUNT 42`, `#define MASK 0x10`, `#define TOTAL (COUNT + MASK)`, and a function-like macro. Assert simple numeric constants are materialized, composed expressions use the Clang evaluation path, and function-like macros are not emitted as ordinary constants.
- [ ] **Step 9: Run `./gradlew test --tests 'org.graphiks.kextract.pipeline.MacroParserIntegrationTest'`**; expected result is failure before implementation and PASS after the fixture-based assertions are complete.
- [ ] **Step 10: Commit** with `git add src/test/kotlin/org/graphiks/kextract/pipeline/FilterTest.kt src/test/kotlin/org/graphiks/kextract/pipeline/ParserPipelineIntegrationTest.kt src/test/kotlin/org/graphiks/kextract/pipeline/MacroParserIntegrationTest.kt && git commit -m "test: cover extraction pipeline components"`.

### Task 4: Add generated Kotlin compilation verification

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/integration/GeneratedBindingsCompilationTest.kt`
- Modify: `build.gradle.kts:170-187` — preserve native test configuration for the dedicated `Test` task.
- Modify: `build.gradle.kts:353-385` — register `verifyGeneratedBindings`.
- Use: `src/test/kotlin/org/graphiks/kextract/testsupport/GeneratedSourceTestSupport.kt`
- Use: `src/test/kotlin/org/graphiks/kextract/testsupport/KotlinCompilerSupport.kt`

**Interfaces:**

```kotlin
@Tag("generated-compile")
class GeneratedBindingsCompilationTest {
    @Test fun `C bindings compile`()
    @Test fun `C split output and init method compile`()
    @Test fun `Objective-C bindings compile on macOS`()
    @Test fun `Win32 bindings compile without loading a DLL`()
}
```

**Steps:**

- [ ] **Step 1: Write the failing C compilation test** for a header containing a struct, union, enum, array, pointer, constant, normal function and configured variadic function. Call `KotlinCompilerSupport.compile(...)` and assert `exitCode == 0`.
- [ ] **Step 2: Run the focused test** with `./gradlew test --tests 'org.graphiks.kextract.integration.GeneratedBindingsCompilationTest'`; expected result is that the class is not yet available.
- [ ] **Step 3: Implement the C compilation test** and use `assertEquals(0, result.exitCode, "${result.stdout}\n${result.stderr}")` so compiler diagnostics are part of failures.
- [ ] **Step 4: Add a split-output/init-method compilation test** using `GenerationRequest(splitOutput = true, useInitMethod = true)` and assert every generated file is passed to `kotlinc` together.
- [ ] **Step 5: Add the macOS-gated Objective-C compilation test** for a class, protocol, property, category and `NSString` method. Compile all generated files, including `ObjCRuntime.kt` and `ObjCSubclassing.kt`.
- [ ] **Step 6: Add the Win32 compilation test** using an in-memory `DllMap`; this test validates Kotlin syntax and types only and never calls a generated native function.
- [ ] **Step 7: Configure Gradle** so ordinary `test` excludes this class and a dedicated `verifyGeneratedBindings` `Test` task includes it:

```kotlin
tasks.named<Test>("test") {
    exclude("**/GeneratedBindingsCompilationTest.class")
}

tasks.register<Test>("verifyGeneratedBindings") {
    group = "verification"
    description = "Generate and compile representative Kotlin bindings."
    dependsOn("testClasses")
    useJUnitPlatform {
        includeTags("generated-compile")
    }
}
```

Keep the existing `tasks.withType<Test>().configureEach` native/JDK setup applicable to both tasks.
- [ ] **Step 8: Run `./gradlew test`** and verify the ordinary suite remains runnable without `kotlinc` being needed by this class.
- [ ] **Step 9: Run `./gradlew verifyGeneratedBindings`** with `kotlinc` available; expected result is PASS for C, Objective-C on macOS and Win32 generation compilation.
- [ ] **Step 10: Commit** with `git add build.gradle.kts src/test/kotlin/org/graphiks/kextract/integration/GeneratedBindingsCompilationTest.kt && git commit -m "test: compile generated Kotlin bindings"`.

### Task 5: Add golden-file regression tests

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/integration/GoldenFileTest.kt`
- Create: `src/test/resources/golden/c/structs-and-functions.h`
- Create: `src/test/resources/golden/c/constants-and-macros.h`
- Create: `src/test/resources/golden/c/variadic.h`
- Create: `src/test/resources/golden/objc/classes-and-protocols.h`
- Create: `src/test/resources/golden/objc/categories-and-properties.h`
- Create: `src/test/resources/golden/win32/sample.h`
- Create: `src/test/resources/golden/win32/sample.yml`
- Create: expected `.kt` files under each fixture directory.
- Modify: `build.gradle.kts:353-385` — register `updateGoldenFiles`.

**Interfaces:**

```kotlin
data class GoldenCase(
    val name: String,
    val root: Path,
    val request: GenerationRequest,
)

fun normalizeGeneratedSource(source: String): String
fun compareOrUpdateGolden(case: GoldenCase, generated: Map<String, String>, update: Boolean)
```

**Steps:**

- [ ] **Step 1: Add the first C fixture** and write a failing `GoldenFileTest` that expects the reference generated Kotlin files to exist.
- [ ] **Step 2: Run `./gradlew test --tests 'org.graphiks.kextract.integration.GoldenFileTest'`**; expected result is failure because the reference files do not exist.
- [ ] **Step 3: Implement normalization** for `\r\n`/`\r` to `\n`, trailing spaces per line and a final newline; do not remove meaningful blank lines or reorder source contents.
- [ ] **Step 4: Implement comparison** by sorting generated paths, loading the matching reference file and reporting missing, unexpected or changed files with a unified-style diff.
- [ ] **Step 5: Register `updateGoldenFiles`** as a dedicated Gradle `Test` task that depends on `testClasses`, includes only `GoldenFileTest`, and sets `golden.update=true`. The ordinary `test` task must not set this property.

```kotlin
tasks.register<Test>("updateGoldenFiles") {
    group = "verification"
    description = "Regenerate declared golden files explicitly."
    dependsOn("testClasses")
    useJUnitPlatform()
    filter {
        includeTestsMatching("org.graphiks.kextract.integration.GoldenFileTest")
    }
    systemProperty("golden.update", "true")
}
```
- [ ] **Step 6: Run `./gradlew updateGoldenFiles`** to create only the declared references, then run `./gradlew test --tests 'org.graphiks.kextract.integration.GoldenFileTest'`; expected result is PASS.
- [ ] **Step 7: Add the remaining C, macOS Objective-C and Win32 fixture cases**. Use separate Objective-C references for macOS-dependent output and include `splitOutput`, `useInitMethod` and `DllMap` requests.
- [ ] **Step 8: Modify one reference temporarily and run the focused test**; expected result is a failure that names the fixture, file and changed content. Restore the reference afterward.
- [ ] **Step 9: Run `./gradlew test` and `./gradlew updateGoldenFiles`**; expected result is normal tests passing and the update task changing only declared golden files.
- [ ] **Step 10: Commit** with `git add build.gradle.kts src/test/kotlin/org/graphiks/kextract/integration/GoldenFileTest.kt src/test/resources/golden && git commit -m "test: add golden generator fixtures"`.

### Task 6: Cover Win32/DLL lookup generation

**Files:**

- Create: `src/test/kotlin/org/graphiks/kextract/integration/Win32GenerationTest.kt`
- Use: `src/test/resources/golden/win32/sample.h`
- Use: `src/test/resources/golden/win32/sample.yml`
- Use: `src/main/kotlin/org/graphiks/kextract/kotlin/builders/KotlinToplevelBuilder.kt`
- Use: `src/main/kotlin/org/graphiks/kextract/cli/Configuration.kt`

**Steps:**

- [ ] **Step 1: Write a failing generation assertion** using a `DllMap` with `kernel32.dll` and `user32.dll` names as fixture labels only, functions and constants assigned to different DLLs, and `structs = [Window]`. Assert generated text contains one `SymbolLookup.libraryLookup(...)` per DLL.
- [ ] **Step 2: Run `./gradlew test --tests 'org.graphiks.kextract.integration.Win32GenerationTest'`**; expected result is failure because the test class is absent.
- [ ] **Step 3: Implement lookup assertions** for the generated `_lookup` branches: each function and constant appears in its configured DLL branch, an unknown symbol uses `loaderLookup()`, and the struct name does not appear in the symbol branch.
- [ ] **Step 4: Add DLL-name cases** containing dots, hyphens and names requiring Kotlin-safe variable names; pass all generated files to `KotlinCompilerSupport` and assert compilation succeeds.
- [ ] **Step 5: Add the deferred initialization case** with `useInitMethod = true`; assert nullable lookup fields, `_initialized` and the generated `init()` path are present, while eager static lookup declarations are absent.
- [ ] **Step 6: Add the ordinary-library regression case** with `win32Mode = false` and one `Options.Library`; assert no per-DLL lookup table is emitted.
- [ ] **Step 7: Run `./gradlew test --tests 'org.graphiks.kextract.integration.Win32GenerationTest'` and `./gradlew verifyGeneratedBindings`**; expected result is PASS without loading any native DLL.
- [ ] **Step 8: Commit** with `git add src/test/kotlin/org/graphiks/kextract/integration/Win32GenerationTest.kt src/test/resources/golden/win32 && git commit -m "test: cover Win32 DLL lookup generation"`.

### Task 7: Attempt optional coverage with a compatibility gate

**Files:**

- Modify: `build.gradle.kts:1-12` only if the compatibility spike succeeds.
- Modify: `build.gradle.kts:153-166` only if the coverage plugin applies cleanly.
- Modify: `build.gradle.kts:170-187` only if report filters and verification are supported.

**Steps:**

- [ ] **Step 1: Establish the current baseline** with `./gradlew cleanTest test --no-daemon`; record the existing test result and do not change Kotlin or Gradle versions.
- [ ] **Step 2: Test Kover `0.9.8`** against Kotlin 2.3.21 and Gradle 9.5.1 in an isolated temporary edit or disposable branch. Start with the official plugin declaration `id("org.jetbrains.kotlinx.kover") version "0.9.8"`, then verify plugin resolution, Kotlin compilation, test execution and HTML report generation.
- [ ] **Step 3: If any compatibility check fails**, remove the experimental plugin edit, record Phase 7 as dropped because of Kotlin compatibility, and skip the remaining steps in this task. Do not add JaCoCo automatically.
- [ ] **Step 4: If all checks pass**, add the pinned plugin and configure the `kmain` source set plus exclusions using the supported Kover DSL:

```kotlin
kover {
    currentProject {
        sources {
            includedSourceSets.addAll("kmain")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    "org.graphiks.kextract.clang.libclang.*",
                    "org.graphiks.kextract.testsupport.*",
                )
            }
        }
    }
}
```

Use the total report tasks `koverHtmlReport` and `koverVerify`; if this DSL does not work with the existing custom `kmain` source set, drop the phase.
- [ ] **Step 5: Generate the first local report** and record line/branch coverage without selecting an arbitrary threshold before measurement.
- [ ] **Step 6: Add a no-regression verification bound** based on the measured baseline only if the plugin supports the required DSL without changing the toolchain.
- [ ] **Step 7: Run `./gradlew test` and the coverage report task**; expected result is both tasks passing and the report present under `build/reports`.
- [ ] **Step 8: Commit only the compatible result** with `git add build.gradle.kts && git commit -m "build: add compatible local coverage reporting"`; if the phase was dropped, make no commit for this task.

### Task 8: Full verification and handoff

**Files:**

- Verify all files changed by Tasks 1–7.
- Do not modify `.github/workflows/test.yml`.

**Steps:**

- [ ] **Step 1: Run formatting/diff checks** with `git diff --check` and `git status --short`; expected result is no whitespace errors and only intentional files changed.
- [ ] **Step 2: Run the complete core suite** with `./gradlew cleanTest test --no-daemon`; expected result is zero failures, zero errors and zero skipped tests.
- [ ] **Step 3: Run generated compilation** with `./gradlew verifyGeneratedBindings --no-daemon`; expected result is PASS when `kotlinc` is installed, otherwise the task fails with its documented actionable message.
- [ ] **Step 4: Run examples** with `./gradlew verifyExamples --no-daemon`; expected result is all existing examples pass on a supported macOS/Linux host with their documented toolchains.
- [ ] **Step 5: Run golden verification** with `./gradlew test --tests 'org.graphiks.kextract.integration.GoldenFileTest'`; expected result is no reference drift.
- [ ] **Step 6: Inspect the final diff** and confirm production source files were not modified unless a separately approved behavioral fix was required by a failing test.
- [ ] **Step 7: Commit any final test-only changes** with a focused message and verify the worktree is clean.

## Spec coverage checklist

- [ ] CLI defaults, options, errors and process entry point — Task 2.
- [ ] `DllMap` YAML model — Task 2.
- [ ] Include and duplicate filtering — Task 3.
- [ ] Unsupported declarations and diagnostics — Task 3.
- [ ] `TreeMaker`, `TypeMaker` and macro parsing — Task 3.
- [ ] Generated C and Objective-C Kotlin compilation — Task 4.
- [ ] Golden output comparison and explicit update command — Task 5.
- [ ] Win32/DLL mapping and deferred initialization — Task 6.
- [ ] Optional coverage with Kotlin compatibility drop rule — Task 7.
- [ ] Local verification commands and no CI changes — Task 8.
