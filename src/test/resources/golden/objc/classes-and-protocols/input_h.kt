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
 * Kotlin/JVM interface for Objective-C protocol: Greeting
 */
interface Greeting {
    fun greet(): MemorySegment

    // @optional
    fun reset(): Unit =
        throw UnsupportedOperationException("Optional ObjC method 'reset' not implemented")

}

/**
 * Kotlin/JVM wrapper for Objective-C class: Person
 * Superclass: NSObject
 * Protocols: Greeting
 */
open class Person(override val ptr: MemorySegment) : NSObject(ptr) {
    companion object {
        private val _class: MemorySegment by lazy { ObjCRuntime.getClass("Person") }

    }

    open fun greetWithPrefix(prefix: MemorySegment): MemorySegment {
        val sel = ObjCRuntime.sel("greetWithPrefix:")
        return ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel, prefix) as MemorySegment
    }

    /** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */
    fun greetWithPrefixAsString(prefix: MemorySegment): String = ObjCRuntime.toJavaString(greetWithPrefix(prefix))

    /** Convenience overload — accepts Kotlin [String] for NSString parameters. */
    fun greetWithPrefix(prefix: String): MemorySegment = greetWithPrefix(ObjCRuntime.newNSString(Arena.global(), prefix))

    /** Convenience overload — [String] parameters and [String] return type. */
    fun greetWithPrefixAsString(prefix: String): String = ObjCRuntime.toJavaString(greetWithPrefix(ObjCRuntime.newNSString(Arena.global(), prefix)))

    // @property name
    open fun name(): MemorySegment {
        val sel = ObjCRuntime.sel("name")
        return ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as MemorySegment
    }
    open fun setName(value: MemorySegment) {
        val sel = ObjCRuntime.sel("setName:")
        ObjCRuntime.msgSend(null, ptr, sel, value)
    }

    /** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */
    open fun nameAsString(): String = ObjCRuntime.toJavaString(name())

    /** Convenience overload — accepts Kotlin [String] for the NSString property. */
    open fun setName(value: String) = setName(ObjCRuntime.newNSString(Arena.global(), value))

}

