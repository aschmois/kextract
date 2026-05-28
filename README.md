# kextract

`kextract` generates **Kotlin/JVM bindings** from C and Objective-C headers.  
It parses headers via [libclang](https://clang.llvm.org/doxygen/group__CINDEX.html) and emits idiomatic Kotlin code that calls native libraries through the [Foreign Function & Memory API](https://openjdk.org/jeps/454) (Panama FFI) — no JNI, no stub generation.

---

## Requirements

| Dependency | Version |
|---|---|
| JDK | 25+ |
| LLVM / libclang | 13+ (download from [releases.llvm.org](https://releases.llvm.org/download.html)) |
| Gradle | 9.5.1 (fetched automatically by the wrapper) |

> **macOS shortcut** — `llvm_home` can point to the Xcode toolchain or the Homebrew LLVM:
> ```sh
> $(brew --prefix llvm)
> /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr
> ```

---

## Building

```sh
./gradlew -Pjdk_home=<jdk_dir> -Pllvm_home=<llvm_dir> clean kmainClasses
```

The build produces a self-contained `kextract` distribution under `build/kextract/`.

---

## Usage

```
kextract [OPTIONS] headers...
```

### Options

| Option | Short | Description |
|---|---|---|
| `--output <dir>` | `-o` | Output directory for generated files (default: `.`) |
| `--target-package <pkg>` | `-t` | Package for generated Kotlin classes |
| `--library <lib>` | `-l` | Library to link against (prefix `:` for a path, e.g. `:/usr/lib/libfoo.so`) |
| `--include-path <dir>` | `-I` | Add a directory to the clang include path |
| `-D <NAME[=VALUE]>` | | Add a preprocessor define |
| `--clang-arg <arg>` | `-A` | Forward an arbitrary argument to clang |
| `--use-system-load-library` | | Use `System.loadLibrary` instead of `SymbolLookup.libraryLookup` |
| `--symbols-class-name <name>` | | Class name for the shared symbols object |
| `--dump-includes <file>` | | Write a reusable `--include-*` filter file |
| `--objc` | | Enable Objective-C mode (`-x objective-c -fobjc-arc`) — macOS only |
| `--include-function <name>` | | Include a specific function |
| `--include-var <name>` | | Include a specific variable |
| `--include-constant <name>` | | Include a specific constant |
| `--include-struct <name>` | | Include a specific struct |
| `--include-union <name>` | | Include a specific union |
| `--include-typedef <name>` | | Include a specific typedef |
| `--include-objc-class <name>` | | Include a specific ObjC class |
| `--include-objc-protocol <name>` | | Include a specific ObjC protocol |
| `--include-objc-category `*`ClassName_CategoryName`* | | Include a specific ObjC category |
| `--help` | `-h` | Print help and exit |
| `--version` | `-V` | Print version and exit |

GNU-style concatenated options (`-DFOO=1`, `-I/path`) are accepted.  
Argument files (`@args.txt`) are supported — one argument per line, `#` comments allowed.

### C example

```sh
# Generate Kotlin bindings for zlib
kextract \
  -t org.example.zlib \
  -o src/generated/kotlin \
  -l z \
  /usr/include/zlib.h
```

This produces `src/generated/kotlin/org/example/zlib/zlib_h.kt` with:

```kotlin
// Memory layout for z_stream
object z_stream_h {
    val layout: StructLayout = MemoryLayout.structLayout(...)

    fun next_in(seg: MemorySegment): MemorySegment = ...
    fun avail_in(seg: MemorySegment): Int = ...
    // ...
}

// Native function bindings
fun deflate(strm: MemorySegment, flush: Int): Int = ...
fun inflate(strm: MemorySegment, flush: Int): Int = ...
```

---

## Objective-C support (macOS)

Pass `--objc` to enable ObjC parsing. kextract maps the full ObjC surface to idiomatic Kotlin:

| ObjC construct | Generated Kotlin |
|---|---|
| `@interface Foo : Bar` | `open class Foo(val ptr: MemorySegment) : Bar(ptr)` |
| `@protocol Drawable` | `interface Drawable` |
| `@interface Foo (Utils)` | top-level extension functions `fun Foo.utilMethod()` |
| `NS_ENUM(NSInteger, Kind)` | `enum class Kind(val value: Long)` with `fromValue()` |
| `NS_OPTIONS(NSInteger, Flags)` | `@JvmInline value class Flags(val rawValue: Long)` with `+`/`in` operators |
| `NSString *` param / return | raw `MemorySegment` overload + `String` convenience overload |

All ObjC object references are `MemorySegment`; the ObjC runtime (`objc_msgSend`, `sel_registerName`, `objc_getClass`) is called via Panama FFI through a generated `ObjCRuntime.kt` helper. No Kotlin/Native required.

### Example

Given this Objective-C header:

```objc
// Shape.h
typedef NS_OPTIONS(NSInteger, ShapeOptions) {
    ShapeOptionFilled   = 1,
    ShapeOptionBordered = 2,
};

typedef NS_ENUM(NSInteger, ShapeKind) {
    ShapeKindRectangle = 1,
    ShapeKindCircle    = 2,
};

@protocol Drawable
- (NSString *)draw;
@end

@interface Shape : NSObject
@property (nonatomic, readonly) double width;
@property (nonatomic, readonly) double height;
+ (instancetype)shapeWithWidth:(double)w height:(double)h;
- (double)area;
@end

@interface Shape (Geometry)
- (double)perimeter;
@end

@interface Rectangle : Shape <Drawable>
+ (instancetype)rectWithWidth:(double)w height:(double)h;
- (NSString *)draw;
@end
```

Run:

```sh
kextract --objc \
  --target-package org.example.shapes \
  --output generated \
  --library :libshapes.dylib \
  --include-objc-class Shape \
  --include-objc-class Rectangle \
  --include-objc-protocol Drawable \
  --include-objc-category Shape_Geometry \
  Shape.h
```

kextract generates:

```kotlin
// NS_OPTIONS — bitflags with + and in operators
@JvmInline value class ShapeOptions(val rawValue: Long) {
    companion object {
        val ShapeOptionFilled   = ShapeOptions(1L)
        val ShapeOptionBordered = ShapeOptions(2L)
    }
    operator fun plus(o: ShapeOptions) = ShapeOptions(rawValue or o.rawValue)
    operator fun contains(o: ShapeOptions) = (rawValue and o.rawValue) != 0L
}

// NS_ENUM — enum class with fromValue()
enum class ShapeKind(val value: Long) {
    ShapeKindRectangle(1L), ShapeKindCircle(2L);
    companion object {
        fun fromValue(v: Long): ShapeKind = entries.firstOrNull { it.value == v }
            ?: error("Unknown ShapeKind value: $v")
    }
}

// @protocol
interface Drawable {
    fun draw(): MemorySegment
}

// @interface with @property and factory class method
open class Shape(val ptr: MemorySegment) {
    companion object {
        fun shapeWithWidth_height(w: Double, h: Double): MemorySegment { ... }
    }
    fun area(): Double { ... }
    // @property width
    fun width(): Double { ... }
    // @property height
    fun height(): Double { ... }
}

// Category — extension functions on Shape
fun Shape.perimeter(): Double { ... }

// Inheritance — Rectangle extends Shape
open class Rectangle(ptr: MemorySegment) : Shape(ptr) {
    companion object {
        fun rectWithWidth_height(w: Double, h: Double): MemorySegment { ... }
    }
    fun draw(): MemorySegment { ... }
    /** Convenience overload — returns Kotlin String by converting the NSString via UTF8String. */
    fun drawAsString(): String = ObjCRuntime.toJavaString(draw())
}
```

Calling the bindings from Kotlin:

```kotlin
import org.example.shapes.*

fun main() {
    // NS_OPTIONS bitflags
    val opts = ShapeOptions.ShapeOptionFilled + ShapeOptions.ShapeOptionBordered
    println(ShapeOptions.ShapeOptionFilled in opts)  // true

    // NS_ENUM
    println(ShapeKind.fromValue(1L))  // ShapeKindRectangle

    // Factory + inherited methods + category extension
    val rect = Rectangle(Rectangle.rectWithWidth_height(4.0, 6.0))
    println(rect.area())        // 24.0  — inherited from Shape
    println(rect.perimeter())   // 20.0  — category extension, works on subtypes
    println(rect.drawAsString()) // Rectangle(4.0 x 6.0)
}
```

> **Always use `--include-objc-class` with system headers.**  
> Foundation, UIKit and AppKit headers pull in the entire framework tree (thousands of `@interface` declarations). Without an `--include-*` flag kextract will try to generate bindings for all of them.  
> Repeat the flag for multiple classes: `--include-objc-class NSString --include-objc-class NSArray`

### Multi-part selectors

ObjC selectors with multiple parts (`shapeWithWidth:height:`) become Kotlin function names with underscores between parts, trailing underscore stripped:

| ObjC selector | Kotlin name |
|---|---|
| `greetWithName:` | `greetWithName` |
| `shapeWithWidth:height:` | `shapeWithWidth_height` |
| `setObject:forKey:` | `setObject_forKey` |

### NSString convenience overloads

Methods and properties whose parameter or return type is `NSString *` get extra overloads that accept or return `kotlin.String` directly:

```kotlin
// Base method (raw):
fun greetWithName(name: MemorySegment): MemorySegment

// Auto-generated overloads:
fun greetWithNameAsString(name: MemorySegment): String   // NSString return → String
fun greetWithName(name: String): MemorySegment           // String param → NSString
fun greetWithNameAsString(name: String): String          // both
```

---

## Examples

| Example | What it covers |
|---|---|
| [`examples/helloworld-c`](examples/helloworld-c) | Generating and calling C bindings (`hello()`, `add()`) |
| [`examples/helloworld-objc`](examples/helloworld-objc) | Basic ObjC class — factory method, `NSString` parameter and return |
| [`examples/objc-comprehensive`](examples/objc-comprehensive) | `NS_OPTIONS` bitflags, `NS_ENUM`, `@protocol`, `@interface` inheritance, category extension functions |

Run any example end-to-end (builds kextract, generates bindings, compiles Kotlin, executes):

```sh
cd examples/helloworld-objc
./run.sh
```

---

## Project structure

```
org.graphiks.kextract          # public model — Declaration, Type, Position
org.graphiks.kextract.pipeline # extraction engine — parser, filters, name mangler, CLI
org.graphiks.kextract.clang    # low-level libclang bindings (auto-generated)
org.graphiks.kextract.kotlin   # Kotlin code generators
```

---

## Testing

```sh
./gradlew -Pjdk_home=<jdk_dir> -Pllvm_home=<llvm_dir> test
```

Tests are written with [JUnit 5](https://junit.org/junit5/) and [Kotest](https://kotest.io/).

---

## License

[MIT](LICENSE)
