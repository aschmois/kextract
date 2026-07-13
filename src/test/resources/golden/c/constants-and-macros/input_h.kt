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
 * {@snippet lang=c : #define BUFFER_SIZE 64
 */
fun BUFFER_SIZE(): Int = (64).toInt()

/**
 * {@snippet lang=c : #define FEATURE_MASK 16
 */
fun FEATURE_MASK(): Int = (16).toInt()

/**
 * {@snippet lang=c : ExplicitLimit Int
 */
private val ExplicitLimit_LAYOUT: ValueLayout by lazy { ValueLayout.JAVA_INT }
private val ExplicitLimit_SEGMENT: MemorySegment by lazy { SymbolLookup.loaderLookup().find("ExplicitLimit").orElseThrow() }
private val ExplicitLimit_VH: VarHandle by lazy { ExplicitLimit_LAYOUT.varHandle() }

var ExplicitLimit: Int
    get() = ExplicitLimit_VH.get(ExplicitLimit_SEGMENT) as Int
    set(value) = ExplicitLimit_VH.set(ExplicitLimit_SEGMENT, value)

/**
 * {@snippet lang=c : #define COMBINED 80
 */
fun COMBINED(): Int = (80).toInt()

