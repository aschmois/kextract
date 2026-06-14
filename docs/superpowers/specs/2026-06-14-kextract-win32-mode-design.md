# kextract `--win32` mode — Design Spec

**Date:** 2026-06-14
**Status:** Draft

## Problem

The Kadre Win32 backend (`ffi/win32`) uses hand-written FFM bindings for
user32.dll, kernel32.dll, gdi32.dll, and dwmapi.dll. The other backends
(Wayland, AppKit/ObjC) use kextract-generated bindings. To align the
codebase and reduce maintenance, we need kextract to generate the Win32
bindings too.

## Solution

Add a `--win32` mode to kextract that generates per-DLL FFM bindings
using `SymbolLookup.libraryLookup()` with cross-platform safety, a
declarative DLL-to-symbol mapping, and automatic inline-function
filtering.

---

## Section 1 — `--win32` flag

A new CLI flag `--win32` that modifies four behaviors in kextract:

1. **libraryLookup mode** — replaces `loaderLookup()` with
   `SymbolLookup.libraryLookup("dllname.dll", Arena.global())`. Each DLL
   gets its own lookup variable.

2. **Cross-platform safety** — each libraryLookup is wrapped in
   `try { ... } catch (_: Throwable) { null }` so the generated code
   compiles on macOS/Linux (where the DLLs don't exist). The lookup
   variable is typed `SymbolLookup?` (nullable).

3. **Inline-function filtering** — functions with `static __inline` or
   `__inline` storage class are silently skipped. No binding is
   generated for them.

4. **DLL mapping** — the `--dll-map` flag loads a YAML file that
   defines which functions/structs/constants belong to which DLL.

Usage:
```bash
kextract --win32 \
  --dll-map win32-dlls.yaml \
  -t org.graphiks.kadre.ffi.win32 \
  -o ffi/win32/src/jvmMain/kotlin \
  win32-kadre.h
```

All existing flags (`--include-function`, `-A`, `-D`, `-I`) remain
compatible.

---

## Section 2 — YAML DLL mapping format (`--dll-map`)

A YAML file that declares which symbols belong to each DLL.

```yaml
dll_map:
  user32.dll:
    functions:
      - CreateWindowExW
      - RegisterClassExW
      - ShowWindow
      - UpdateWindow
      - DestroyWindow
      - DefWindowProcW
      - GetMessageW
      - PeekMessageW
      - TranslateMessage
      - DispatchMessageW
      - PostQuitMessage
      - SetWindowTextW
      - GetWindowTextW
      - GetKeyState
      - LoadCursorW
      - SetCursor
      - SetCursorPos
      - GetCursorPos
      - ShowCursor
      - ClipCursor
      - ScreenToClient
      - ClientToScreen
      - TrackMouseEvent
      - GetClientRect
      - GetWindowRect
      - SetWindowPos
      - SetWindowLongPtrW
      - GetWindowLongPtrW
      - EnableWindow
      - IsZoomed
      - IsIconic
      - IsWindowVisible
      - SetForegroundWindow
      - GetForegroundWindow
      - SendMessageW
      - PostMessageW
      - GetSystemMenu
      - TrackPopupMenu
      - EnableMenuItem
      - SetMenuDefaultItem
      - ReleaseCapture
      - RegisterTouchWindow
      - GetTouchInputInfo
      - CloseTouchInputHandle
      - GetGestureInfo
      - CloseGestureInfoHandle
      - MapVirtualKeyW
      - SendInput
      - SetProcessDpiAwarenessContext
      - GetDpiForWindow
      - CreateIcon
      - DestroyIcon
      - GetCurrentThreadId
    structs:
      - WNDCLASSEXW
      - MSG
      - POINT
      - RECT
      - TRACKMOUSEEVENT
    constants:
      - WS_OVERLAPPEDWINDOW
      - WS_EX_APPWINDOW
      - WS_EX_TOOLWINDOW
      - SW_SHOW
      - SW_HIDE
      - SW_MINIMIZE
      - SW_RESTORE
      - SW_MAXIMIZE
      - WM_DESTROY
      - WM_NCLBUTTONDOWN
      - WM_SYSCOMMAND
      - WM_APP
      - WM_KADRE_NON_CLIENT_DRAG
      - TPM_RETURNCMD
      - TPM_LEFTALIGN
      - SC_SIZE
      - SC_MOVE
      - SC_MINIMIZE
      - SC_MAXIMIZE
      - SC_CLOSE
      - SC_RESTORE
      - MF_BYCOMMAND
      - MF_ENABLED
      - MF_DISABLED
      - HTCAPTION
      - HTLEFT
      - HTRIGHT
      - HTTOP
      - HTTOPLEFT
      - HTTOPRIGHT
      - HTBOTTOM
      - HTBOTTOMLEFT
      - HTBOTTOMRIGHT
      - GWL_STYLE
      - WS_THICKFRAME
      - WS_CAPTION
      - WS_BORDER
      - WS_SYSMENU
      - WS_MINIMIZEBOX
      - WS_MAXIMIZEBOX
      - WS_VISIBLE
      - SWP_NOSIZE
      - SWP_NOMOVE
      - SWP_NOZORDER
      - SWP_NOACTIVATE
      - SWP_FRAMECHANGED
      - INPUT_KEYBOARD
      - INPUT_SIZE
      - MAPVK_VK_TO_VSC
      - KEYEVENTF_EXTENDEDKEY
      - KEYEVENTF_KEYUP
      - CS_HREDRAW_VREDRAW
      - DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
      - IDC_ARROW
      - IDC_WAIT
      - IDC_IBEAM
      - IDC_CROSS
      - IDC_SIZEALL
      - IDC_NO
      - IDC_HAND
      - IDC_APPSTARTING
      - IDC_HELP
      - IDC_SIZENS
      - IDC_SIZEWE
      - IDC_SIZENWSE
      - IDC_SIZENESW
      - TME_LEAVE
      - TRACKMOUSEEVENT_SIZE
      - RECT_SIZE
      - RECT_ALIGN
      - POINT_SIZE
      - POINT_ALIGN

  kernel32.dll:
    functions:
      - GetModuleHandleW
      - GetLastError
      - SetLastError
    constants: []

  gdi32.dll:
    functions:
      - CreateRectRgn
      - DeleteObject
    constants: []

  dwmapi.dll:
    functions:
      - DwmSetWindowAttribute
      - DwmEnableBlurBehindWindow
      - DwmExtendFrameIntoClientArea
    constants:
      - DWM_BB_ENABLE
      - DWM_BB_BLURREGION
      - DWM_BLURBEHIND_SIZE
      - DWM_BLURBEHIND_ALIGN
      - DWM_BLURBEHIND_OFFSET_DW_FLAGS
      - DWM_BLURBEHIND_OFFSET_F_ENABLE
      - DWM_BLURBEHIND_OFFSET_H_RGN_BLUR
      - DWM_BLURBEHIND_OFFSET_F_TRANSITION_ON_MAXIMIZED
      - DWMWA_USE_IMMERSIVE_DARK_MODE
      - DWMWA_WINDOW_CORNER_PREFERENCE
      - DWMWA_BORDER_COLOR
      - DWMWA_CAPTION_COLOR
      - DWMWA_TEXT_COLOR
      - DWMWA_SYSTEMBACKDROP_TYPE
      - DWMWA_MICA
```

Rules:
- `constants:` are simple `#define` scalar values. Complex macros are
  ignored (filtered by clang's AST — kextract only sees integer/float
  literals).
- `structs:` are optional. If omitted, kextract only generates accessor
  layouts for structs referenced by function signatures.
- `constants: []` is explicit — kextract will not scan for extra symbols.

---

## Section 3 — Generated code structure

Each DLL produces one Kotlin file, named after the DLL:

| DLL | File |
|-----|------|
| `user32.dll` | `User32_h.kt` |
| `kernel32.dll` | `Kernel32_h.kt` |
| `gdi32.dll` | `Gdi32_h.kt` |
| `dwmapi.dll` | `Dwmapi_h.kt` |

### File structure (example: `User32_h.kt`)

```kotlin
package org.graphiks.kadre.ffi.win32

import java.lang.foreign.*
import java.lang.invoke.MethodHandle

private object kextract_runtime {
    val C_BOOL = ValueLayout.JAVA_BOOLEAN
    val C_INT = ValueLayout.JAVA_INT
    val C_LONG = ValueLayout.JAVA_LONG
    val C_POINTER = ValueLayout.ADDRESS
}

private val user32: SymbolLookup? by lazy {
    try {
        SymbolLookup.libraryLookup("user32.dll", Arena.global())
    } catch (_: Throwable) { null }
}

// ── WNDCLASSEXW ──
object WNDCLASSEXW_h {
    val layout: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbSize"),
        // ...
    )
    fun cbSize(seg: MemorySegment): Int = seg.get(ValueLayout.JAVA_INT, 0L)
    // ...
}

// ── CreateWindowExW ──
fun CreateWindowExW(/*...*/): MemorySegment {
    val lo = checkNotNull(user32) { "user32.dll not available" }
    val handle = lo.downcallHandle("CreateWindowExW", FunctionDescriptor.of(/*...*/))
    return handle.invokeExact(/*...*/) as MemorySegment
}
```

### Notes

- The `kextract_runtime` object can be shared across DLL files or
  duplicated — TBD during implementation based on generated code size.
- Functions throw `IllegalStateException("user32.dll not available")`
  when called on a non-Windows platform (vs silently returning null).
- Struct layout accessors follow kextract's existing pattern:
  `StructName_h` object with `layout`, field getters, `allocate`,
  `asSlice`, `reinterpret` helpers.

---

## Section 4 — Script d'extraction du mapping

`scripts/analyze-win32-bindings.main.kts` parses the existing hand-written
binding and generates the YAML mapping file.

### What it does

1. Reads `ffi/win32/.../Win32_h.kt`
2. Detects DLL sections via comments (`// ── user32 ──`, `// ── kernel32 ──`)
   or by scanning which `val foo: SymbolLookup?` each function uses
3. Extracts function names from `val foo: MethodHandle?` declarations
4. Extracts `const val` declarations as constants
5. Extracts struct layouts from documented struct comments
6. Outputs `win32-dlls.yaml`

### Usage

```bash
# Generate mapping from hand-written binding
kotlin scripts/analyze-win32-bindings.main.kts \
  -i ffi/win32/src/jvmMain/kotlin/.../Win32_h.kt \
  -o win32-dlls.yaml

# Compare hand-written vs kextract-generated
kotlin scripts/analyze-win32-bindings.main.kts \
  --compare \
  --hand-written ffi/win32/src/jvmMain/kotlin/.../Win32_h.kt \
  --generated ffi/win32/build/generated/
```

### Output

`win32-dlls.yaml` — the full mapping file (see Section 2).

---

## Section 5 — Validation & CI

### Workflow: `regen-win32-bindings.yml`

Modeled on `regen-objc-bindings.yml` but creates a branch + PR instead
of committing directly, so generated bindings can be reviewed before
landing.

```yaml
name: Regenerate Win32 bindings
on: [workflow_dispatch]

jobs:
  regen:
    runs-on: windows-latest
    permissions:
      contents: write
      pull-requests: write

    steps:
      - uses: actions/checkout@v4
        with: { submodules: recursive }
      - uses: actions/setup-java@v4
        with: { distribution: 'zulu', java-version: '25', cache: 'gradle' }

      # Build kextract from our fork/branch
      - run: .\gradlew createKextractImage --no-daemon
        working-directory: third_party/kextract

      # Generate Win32 bindings
      - run: |
          .\scripts\regen-win32-bindings.cmd
        shell: cmd

      # Create a branch and PR if there are changes
      - name: Create PR with regenerated bindings
        if: steps.changes.outputs.changed == 'true'
        run: |
          $branch = "regen/win32-bindings-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
          git checkout -b $branch
          git add ffi/win32/
          git config user.name "Kadre Bot"
          git config user.email "bot@graphiks.org"
          git commit -m "regen: update Win32 FFM bindings"
          git push origin $branch
          gh pr create --fill --label "bindings" --base master
        env:
          GH_TOKEN: ${{ github.token }}
```

### Validation steps

1. **Build check** — `./gradlew :ffi:win32:compileKotlinJvm` succeeds
2. **Fidelity check** — `scripts/analyze-win32-bindings.kts --compare` shows
   no unexpected differences (or documents intentional diffs)
3. **Test check** — `./gradlew :kadre-win32:jvmTest` passes

### Deployment phases

| Phase | Action |
|-------|--------|
| 1 | Add `--win32` flag to kextract (fork + PR upstream) |
| 2 | Write `win32-dlls.yaml` and `scripts/analyze-win32-bindings.kts` |
| 3 | Generate bindings alongside hand-written (`ffi/win32/generated/`) |
| 4 | Verify compilation + tests + fidelity |
| 5 | Replace hand-written with generated bindings, remove old code |
| 6 | Add `regen-win32-bindings.yml` workflow |

---

## Section 6 — Git workflow

### kextract (upstream project)

```
klang-toolkit/kextract (upstream)
         ▲
         │ PR (win32-mode → upstream master)
         │
graphiks/kextract (fork)
         │
         └── win32-mode branch   ←── third_party/kextract submodule pointe ici
```

1. **Fork** `klang-toolkit/kextract` → `graphiks/kextract`
2. **Branch `win32-mode`** sur le fork, implémente `--win32` + `--dll-map`
3. **PR** vers `klang-toolkit/kextract` (upstream) quand la feature est stable
4. Pendant ce temps, **Kadre pointe son submodule** vers `graphiks/kextract/win32-mode`

### Kadre (ce repo)

La feature Kadre se développe sur une branche feature normale :

```
kadre/win32-kextract-bindings (branche feature)
  ├── third_party/kextract → graphiks/kextract/win32-mode
  ├── scripts/regen-win32-bindings.sh
  ├── win32-dlls.yaml
  ├── scripts/analyze-win32-bindings.main.kts
  └── .github/workflows/regen-win32-bindings.yml
```

Une fois le PR kextract mergé upstream, on resynchronise le submodule :
```
git -C third_party/kextract fetch origin
git -C third_party/kextract checkout tags/v0.0.3  # ou commit hash du merge
git add third_party/kextract && git commit -m "chore: sync kextract submodule to v0.0.3"
```

---

## Implementation plan

1. Fork `klang-toolkit/kextract` into `graphiks/kextract`, create `win32-mode` branch
2. Implement `--win32` flag + `--dll-map` in kextract
3. Point Kadre's submodule to the fork branch
4. Write the extraction script and YAML mapping
5. Generate bindings, validate, replace hand-written
6. Add `regen-win32-bindings.yml` CI workflow
7. Submit PR to upstream kextract
