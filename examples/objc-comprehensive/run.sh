#!/usr/bin/env bash
# objc-comprehensive — kextract example covering ObjC inheritance, protocols,
#                      categories, NS_ENUM and NS_OPTIONS (bitflags).
# Usage: ./run.sh [--skip-build]
#
# Requires: macOS, Xcode Command Line Tools, kotlinc on PATH (brew install kotlin)
set -euo pipefail

if [[ "$(uname)" != "Darwin" ]]; then
    echo "✗ This example requires macOS (Objective-C runtime)." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KEXTRACT="$ROOT/build/kextract/bin/kextract"

# ── 1. Build kextract ────────────────────────────────────────────────────────
if [[ "${1:-}" != "--skip-build" ]]; then
    echo "▶ Building kextract…"
    cd "$ROOT"
    ./gradlew createKextractImage
    cd "$SCRIPT_DIR"
    echo "✓ kextract built"
fi

if [[ ! -x "$KEXTRACT" ]]; then
    echo "✗ kextract binary not found at $KEXTRACT" >&2
    exit 1
fi

# ── 2. Compile the ObjC library ──────────────────────────────────────────────
echo "▶ Compiling Shape.m…"
cd "$SCRIPT_DIR"
clang -shared -fPIC -fobjc-arc \
    -framework Foundation \
    -o libshapes.dylib \
    Shape.m
echo "✓ libshapes.dylib"

# ── 3. Generate Kotlin bindings ──────────────────────────────────────────────
echo "▶ Generating Kotlin bindings (--objc)…"
SDK="$(xcrun --show-sdk-path 2>/dev/null || true)"
rm -rf generated
"$KEXTRACT" \
    --objc \
    --target-package org.example.shapes \
    --output generated \
    --library :libshapes.dylib \
    --include-objc-class Shape \
    --include-objc-class Rectangle \
    --include-objc-protocol Drawable \
    --include-objc-category Shape_Geometry \
    ${SDK:+-A "-isysroot" -A "$SDK"} \
    Shape.h
echo "✓ Bindings in generated/"

# ── 4. Detect kotlinc ────────────────────────────────────────────────────────
KOTLINC=""
for candidate in \
    "${KOTLIN_HOME:-}/bin/kotlinc" \
    "$(which kotlinc 2>/dev/null || true)" \
    "/opt/homebrew/bin/kotlinc"; do
    if [[ -x "$candidate" ]]; then
        KOTLINC="$candidate"
        break
    fi
done

if [[ -z "$KOTLINC" ]]; then
    echo ""
    echo "✗ kotlinc not found. Install it with:"
    echo "    brew install kotlin"
    echo "  or set KOTLIN_HOME to your Kotlin installation."
    exit 1
fi
echo "✓ Using kotlinc: $KOTLINC"

# ── 5. Collect generated sources + Main.kt ───────────────────────────────────
SOURCES=(Main.kt)
while IFS= read -r -d '' f; do
    SOURCES+=("$f")
done < <(find generated -name "*.kt" -print0)

# ── 6. Compile ───────────────────────────────────────────────────────────────
echo "▶ Compiling Kotlin…"
rm -rf out && mkdir out
"$KOTLINC" "${SOURCES[@]}" -include-runtime -d out/app.jar 2>&1
echo "✓ out/app.jar"

# ── 7. Run ───────────────────────────────────────────────────────────────────
# SymbolLookup.libraryLookup(name, arena) bypasses java.library.path on Linux
# (and on some macOS configurations) and goes straight to dlopen. Export the
# loader search paths so libshapes.dylib is findable at runtime.
export LD_LIBRARY_PATH="$SCRIPT_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export DYLD_LIBRARY_PATH="$SCRIPT_DIR${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
echo "▶ Running…"
echo ""
"$ROOT/build/kextract/runtime/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$SCRIPT_DIR" \
    -jar out/app.jar
