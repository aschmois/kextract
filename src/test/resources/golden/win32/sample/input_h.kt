import java.lang.invoke.*
import java.lang.foreign.*
import java.lang.foreign.MemoryLayout.PathElement.*

private object kextract_runtime {
    val C_BOOL: ValueLayout = ValueLayout.JAVA_BOOLEAN
    val C_CHAR: ValueLayout = ValueLayout.JAVA_BYTE
    val C_SHORT: ValueLayout = ValueLayout.JAVA_SHORT
    val C_INT: ValueLayout = ValueLayout.JAVA_INT
    val C_LONG: ValueLayout = ValueLayout.JAVA_LONG
    val C_LONG_LONG: ValueLayout = ValueLayout.JAVA_LONG
    val C_FLOAT: ValueLayout = ValueLayout.JAVA_FLOAT
    val C_DOUBLE: ValueLayout = ValueLayout.JAVA_DOUBLE
    val C_POINTER: ValueLayout = ValueLayout.ADDRESS
}

private var _initialized: Boolean = false

private var _DLL_KERNEL32_DLL: SymbolLookup? = null
private var _DLL_USER32_DLL: SymbolLookup? = null

private fun _lookup(symbol: String): SymbolLookup {
    return when (symbol) {
        "GetWindowText" -> _DLL_KERNEL32_DLL ?: SymbolLookup.loaderLookup()
        "CreateWindow", "WindowStyle" -> _DLL_USER32_DLL ?: SymbolLookup.loaderLookup()
        else -> SymbolLookup.loaderLookup()
    }
}


/**
 * Initializes all Win32 bindings.
 * Must be called before any binding function on Windows.
 * Safe to call on non-Windows (no-op, all symbols stay null).
 */
fun init() {
    if (_initialized) return
    _initialized = true

    _DLL_KERNEL32_DLL = try {
        SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
    } catch (ex: Throwable) {
        null
    }
    _DLL_USER32_DLL = try {
        SymbolLookup.libraryLookup("user32.dll", Arena.global())
    } catch (ex: Throwable) {
        null
    }
    WindowStyle_SEGMENT = _lookup("WindowStyle").find("WindowStyle").orElse(null)
    WindowStyle_VH = WindowStyle_SEGMENT?.let { WindowStyle_LAYOUT.varHandle() }
    CreateWindow_HANDLE = _lookup("CreateWindow").find("CreateWindow").map { Linker.nativeLinker().downcallHandle(it, CreateWindow_DESC) }.orElse(null)
    GetWindowText_HANDLE = _lookup("GetWindowText").find("GetWindowText").map { Linker.nativeLinker().downcallHandle(it, GetWindowText_DESC) }.orElse(null)
}

