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
 * {@snippet lang=c : STRUCT Point
 */
class Point {
    companion object {
        val layout: GroupLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y")
        ).withName("Point")

        val byteSize: Long
            get() = layout.byteSize()

        fun allocate(allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(layout)

        fun allocateArray(elementCount: Long, allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(MemoryLayout.sequenceLayout(elementCount, layout))

        fun asSlice(array: MemorySegment, index: Long): MemorySegment =
            array.asSlice(byteSize * index)

        fun reinterpret(addr: MemorySegment): MemorySegment =
            addr.reinterpret(byteSize)

        fun reinterpret(addr: MemorySegment, elementCount: Long): MemorySegment =
            addr.reinterpret(byteSize * elementCount)

    } // End companion object

    val x_VH: VarHandle = layout.varHandle(groupElement("x"))

    @Suppress("UNCHECKED_CAST")
    fun x(segment: MemorySegment): Int =
        x_VH.get(segment, 0L) as Int

    fun x(segment: MemorySegment, value: Int) =
        x_VH.set(segment, 0L, value)

    val y_VH: VarHandle = layout.varHandle(groupElement("y"))

    @Suppress("UNCHECKED_CAST")
    fun y(segment: MemorySegment): Int =
        y_VH.get(segment, 0L) as Int

    fun y(segment: MemorySegment, value: Int) =
        y_VH.set(segment, 0L, value)
} // End class

/**
 * {@snippet lang=c : STRUCT SampleArray
 */
class SampleArray {
    companion object {
        val layout: GroupLayout = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("values")
        ).withName("SampleArray")

        val byteSize: Long
            get() = layout.byteSize()

        fun allocate(allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(layout)

        fun allocateArray(elementCount: Long, allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(MemoryLayout.sequenceLayout(elementCount, layout))

        fun asSlice(array: MemorySegment, index: Long): MemorySegment =
            array.asSlice(byteSize * index)

        fun reinterpret(addr: MemorySegment): MemorySegment =
            addr.reinterpret(byteSize)

        fun reinterpret(addr: MemorySegment, elementCount: Long): MemorySegment =
            addr.reinterpret(byteSize * elementCount)

    } // End companion object

    fun values(segment: MemorySegment): MemorySegment =
        segment.asSlice(layout.byteOffset(groupElement("values")), layout.select(groupElement("values")).byteSize())
} // End class

/**
 * WARNING: This was originally a C union. Fields overlap in memory!
 * {@snippet lang=c : UNION Number
 */
/**
 * {@snippet lang=c : UNION Number
 */
class Number_ {
    companion object {
        val layout: GroupLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("integer"),
            ValueLayout.JAVA_FLOAT.withName("decimal")
        ).withName("Number")

        val byteSize: Long
            get() = layout.byteSize()

        fun allocate(allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(layout)

        fun allocateArray(elementCount: Long, allocator: SegmentAllocator): MemorySegment =
            allocator.allocate(MemoryLayout.sequenceLayout(elementCount, layout))

        fun asSlice(array: MemorySegment, index: Long): MemorySegment =
            array.asSlice(byteSize * index)

        fun reinterpret(addr: MemorySegment): MemorySegment =
            addr.reinterpret(byteSize)

        fun reinterpret(addr: MemorySegment, elementCount: Long): MemorySegment =
            addr.reinterpret(byteSize * elementCount)

    } // End companion object

    val integer_VH: VarHandle = layout.varHandle(groupElement("integer"))

    @Suppress("UNCHECKED_CAST")
    fun integer(segment: MemorySegment): Int =
        integer_VH.get(segment, 0L) as Int

    fun integer(segment: MemorySegment, value: Int) =
        integer_VH.set(segment, 0L, value)

    val decimal_VH: VarHandle = layout.varHandle(groupElement("decimal"))

    @Suppress("UNCHECKED_CAST")
    fun decimal(segment: MemorySegment): Float =
        decimal_VH.get(segment, 0L) as Float

    fun decimal(segment: MemorySegment, value: Float) =
        decimal_VH.set(segment, 0L, value)
} // End class

/**
 * NS_ENUM: {@snippet lang=c : enum Color}
 */
enum class Color(val value: Long) {
    Red(0L), Green(1L), Blue(2L);

    companion object {
        fun fromValue(v: Long): Color = entries.firstOrNull { it.value == v }
            ?: error("Unknown Color value: $v")
    }
}

/**
 * {@snippet lang=c : add Int(Int,Int)
 */
private val add_DESC: FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
private val add_ADDR: MemorySegment = SymbolLookup.loaderLookup().find("add").orElseThrow()
private val add_HANDLE: MethodHandle = Linker.nativeLinker().downcallHandle(add_ADDR, add_DESC)

fun add(arg0: Int, arg1: Int): Int {
    try {
        return add_HANDLE.invokeExact(arg0, arg1) as Int
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        throw AssertionError("should not reach here", ex)
    }
}

/**
 * {@snippet lang=c : log_message Void((Char)*)
 */
private val log_message_DESC: FunctionDescriptor = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
private val log_message_ADDR: MemorySegment = SymbolLookup.loaderLookup().find("log_message").orElseThrow()
private val log_message_HANDLE: MethodHandle = Linker.nativeLinker().downcallHandle(log_message_ADDR, log_message_DESC)

fun log_message(arg0: MemorySegment): Unit {
    try {
        log_message_HANDLE.invokeExact(arg0)
    } catch (ex: Error) {
        throw ex
    } catch (ex: RuntimeException) {
        throw ex
    } catch (ex: Throwable) {
        throw AssertionError("should not reach here", ex)
    }
}

