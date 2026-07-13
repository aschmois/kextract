import java.lang.invoke.*
import java.lang.foreign.*
import java.lang.foreign.MemoryLayout.PathElement.*

/**
 * {@snippet lang=c : STRUCT Window
 */
class Window {
    companion object {
        val layout: GroupLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height")
        ).withName("Window")

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

    val width_VH: VarHandle = layout.varHandle(groupElement("width"))

    @Suppress("UNCHECKED_CAST")
    fun width(segment: MemorySegment): Int =
        width_VH.get(segment, 0L) as Int

    fun width(segment: MemorySegment, value: Int) =
        width_VH.set(segment, 0L, value)

    val height_VH: VarHandle = layout.varHandle(groupElement("height"))

    @Suppress("UNCHECKED_CAST")
    fun height(segment: MemorySegment): Int =
        height_VH.get(segment, 0L) as Int

    fun height(segment: MemorySegment, value: Int) =
        height_VH.set(segment, 0L, value)
} // End class

