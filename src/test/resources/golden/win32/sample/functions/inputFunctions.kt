import java.lang.invoke.*
import java.lang.foreign.*
import java.lang.foreign.MemoryLayout.PathElement.*

/**
 * {@snippet lang=c : WindowStyle Int
 */
internal val WindowStyle_LAYOUT: ValueLayout by lazy { ValueLayout.JAVA_INT }
internal var WindowStyle_SEGMENT: MemorySegment? = null
internal var WindowStyle_VH: VarHandle? = null

var WindowStyle: Int
    get() {
        check(_initialized) { "Win32 WindowStyle accessed before init()" }
        val _seg = WindowStyle_SEGMENT ?: return 0
        return WindowStyle_VH!!.get(_seg) as Int
    }
    set(value) {
        check(_initialized) { "Win32 WindowStyle accessed before init()" }
        val _seg = WindowStyle_SEGMENT ?: return
        WindowStyle_VH!!.set(_seg, value)
    }

/**
 * {@snippet lang=c : CreateWindow Int(Int,Int)
 */
internal val CreateWindow_DESC: FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
internal var CreateWindow_HANDLE: MethodHandle? = null

fun CreateWindow(arg0: Int, arg1: Int): Int {
    check(_initialized) { "Win32 CreateWindow called before init()" }
    val _handle = CreateWindow_HANDLE ?: return 0
    try {
        return _handle.invokeExact(arg0, arg1) as Int
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        return 0
    }
}

/**
 * {@snippet lang=c : GetWindowText Int(Int,(Char)*,Int)
 */
internal val GetWindowText_DESC: FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
internal var GetWindowText_HANDLE: MethodHandle? = null

fun GetWindowText(arg0: Int, arg1: MemorySegment, arg2: Int): Int {
    check(_initialized) { "Win32 GetWindowText called before init()" }
    val _handle = GetWindowText_HANDLE ?: return 0
    try {
        return _handle.invokeExact(arg0, arg1, arg2) as Int
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        return 0
    }
}

