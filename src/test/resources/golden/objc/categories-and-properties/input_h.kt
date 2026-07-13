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
 * Kotlin/JVM wrapper for Objective-C class: NSObject
 */
open class NSObject(open val ptr: MemorySegment) {
    companion object {
        private val _class: MemorySegment by lazy { ObjCRuntime.getClass("NSObject") }

    }

}

/**
 * Kotlin/JVM wrapper for Objective-C class: Widget
 * Superclass: NSObject
 */
open class Widget(override val ptr: MemorySegment) : NSObject(ptr) {
    companion object {
        private val _class: MemorySegment by lazy { ObjCRuntime.getClass("Widget") }

    }

    open fun refresh(): Unit {
        val sel = ObjCRuntime.sel("refresh")
        ObjCRuntime.msgSend(null, ptr, sel)
    }

    // @property identifier
    open fun identifier(): Int {
        val sel = ObjCRuntime.sel("identifier")
        return ObjCRuntime.msgSend(ValueLayout.JAVA_INT, ptr, sel) as Int
    }

    // @property title
    open fun title(): MemorySegment {
        val sel = ObjCRuntime.sel("title")
        return ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as MemorySegment
    }
    open fun setTitle(value: MemorySegment) {
        val sel = ObjCRuntime.sel("setTitle:")
        ObjCRuntime.msgSend(null, ptr, sel, value)
    }

    /** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */
    open fun titleAsString(): String = ObjCRuntime.toJavaString(title())

    /** Convenience overload — accepts Kotlin [String] for the NSString property. */
    open fun setTitle(value: String) = setTitle(ObjCRuntime.newNSString(Arena.global(), value))

}

// ── Category: Lifecycle on Widget ─────────────────────────────────────────

fun Widget.reset(): Unit {
    val sel = ObjCRuntime.sel("reset")
    ObjCRuntime.msgSend(null, this.ptr, sel)
}

// Class method: +[Widget widgetWithIdentifier:]
fun Widget_widgetWithIdentifier(identifier: Int): MemorySegment {
    val sel = ObjCRuntime.sel("widgetWithIdentifier:")
    val cls = ObjCRuntime.getClass("Widget")
    return ObjCRuntime.msgSend(ValueLayout.ADDRESS, cls, sel, identifier) as MemorySegment
}

