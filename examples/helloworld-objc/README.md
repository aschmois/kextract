# helloworld-objc

Demonstrates generating Kotlin/JVM bindings from an Objective-C header using
kextract's ObjC mode, then calling the native library from Kotlin via
Panama FFI (no JNI, no Kotlin/Native).

## What the example does

1. Compiles `Greeter.m` (a simple `NSObject` subclass) into `libgreeter.dylib`.
2. Runs kextract with `--objc` and `--include-objc-class Greeter` to generate
   Kotlin bindings into `generated/`.
3. Compiles `Main.kt` (which calls `Greeter.create()` and `greetWithName:`)
   together with the generated sources into `out/app.jar`.
4. Runs the jar and prints `Hello, World!`.

## Requirements

- macOS with Xcode Command Line Tools installed
- `kotlinc` on `PATH` (install via `brew install kotlin`)
- kextract built from source (`./gradlew createKextractImage` at the project root)

## Running

```sh
cd examples/helloworld-objc
./run.sh
```

Pass `--skip-build` if you have already built kextract:

```sh
./run.sh --skip-build
```

## The `--include-objc-class` flag

The kextract invocation inside `run.sh` uses:

```sh
"$KEXTRACT" \
    --objc \
    --target-package org.example.greeter \
    --output generated \
    --library :libgreeter.dylib \
    --include-objc-class Greeter \
    ${SDK:+-A "-isysroot" -A "$SDK"} \
    Greeter.h
```

`Greeter.h` starts with `#import <Foundation/Foundation.h>`, which transitively
pulls in a large portion of the Foundation framework. Without
`--include-objc-class Greeter`, kextract would attempt to generate bindings for
every `@interface` visible in those headers — potentially thousands of
declarations — which is almost certainly not what you want and will likely fail
or produce unusable output.

`--include-objc-class` acts as an allowlist: only the named class (and any
types it directly depends on) is emitted. Repeat the flag to include multiple
classes:

```sh
--include-objc-class NSString --include-objc-class NSArray
```

The same pattern applies whenever you parse system framework headers
(Foundation, UIKit, AppKit, CoreData, …). Always use `--include-objc-class`
(and/or `--include-objc-protocol`) to restrict output to exactly the
declarations you need.
