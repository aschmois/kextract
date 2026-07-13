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

/**
 * {@snippet lang=c : typedef (Void(Int,(Char)*))* LogCallback;}
 */
typealias LogCallback = MemorySegment

/**
 * {@snippet lang=c : log_values Void(Int)
 */
private val log_values_DESC: FunctionDescriptor = FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
private val log_values_ADDR: MemorySegment = SymbolLookup.loaderLookup().find("log_values").orElseThrow()
private val log_values_HANDLE: MethodHandle = Linker.nativeLinker().downcallHandle(
    log_values_ADDR, log_values_DESC,
    Linker.Option.firstVariadicArg(1),
)

fun log_values(arg0: Int, arg1: MemorySegment, arg2: MemorySegment): Unit {
    try {
        log_values_HANDLE.invokeExact(arg0, arg1, arg2)
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        throw AssertionError("should not reach here", ex)
    }
}

/**
 * {@snippet lang=c : format_message Int((Char)*,Int)
 */
private val format_message_DESC: FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
private val format_message_ADDR: MemorySegment = SymbolLookup.loaderLookup().find("format_message").orElseThrow()
private val format_message_HANDLE: MethodHandle = Linker.nativeLinker().downcallHandle(
    format_message_ADDR, format_message_DESC,
    Linker.Option.firstVariadicArg(2),
)

fun format_message(arg0: MemorySegment, arg1: Int, arg2: MemorySegment, arg3: MemorySegment, arg4: MemorySegment): Int {
    try {
        return format_message_HANDLE.invokeExact(arg0, arg1, arg2, arg3, arg4) as Int
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        throw AssertionError("should not reach here", ex)
    }
}

