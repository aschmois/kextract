package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KotlinCallbackCAbiTypeTest {
    @Test
    fun `rejects long because its width varies by target`() {
        assertUnsupportedVariableWidthScalar(
            Type.primitive(Type.Primitive.Kind.Long),
            "Unsupported multiplatform callback C ABI scalar 'long': " +
                "target-dependent width (LP64 vs LLP64); use a fixed-width C integer type",
        )
    }

    @Test
    fun `rejects unsigned long through the same variable-width branch`() {
        assertUnsupportedVariableWidthScalar(
            Type.qualified(
                Type.Delegated.Kind.UNSIGNED,
                Type.primitive(Type.Primitive.Kind.Long),
            ),
            "Unsupported multiplatform callback C ABI scalar 'long': " +
                "target-dependent width (LP64 vs LLP64); use a fixed-width C integer type",
        )
    }

    @Test
    fun `rejects long double because its representation varies by target`() {
        assertUnsupportedVariableWidthScalar(
            Type.primitive(Type.Primitive.Kind.LongDouble),
            "Unsupported multiplatform callback C ABI scalar 'long double': " +
                "target-dependent size and format; use double or an explicit fixed-width representation",
        )
    }

    @Test
    fun `accepts stable 64-bit integer and double carriers`() {
        assertEquals(
            ScalarShape("I64", unsigned = false),
            scalarShape(abiType(Type.primitive(Type.Primitive.Kind.LongLong))),
        )
        assertEquals(
            ScalarShape("I64", unsigned = true),
            scalarShape(
                abiType(
                    Type.qualified(
                        Type.Delegated.Kind.UNSIGNED,
                        Type.primitive(Type.Primitive.Kind.LongLong),
                    ),
                ),
            ),
        )
        assertEquals(
            ScalarShape("F64", unsigned = false),
            scalarShape(abiType(Type.primitive(Type.Primitive.Kind.Double))),
        )
    }

    private fun assertUnsupportedVariableWidthScalar(type: Type, expectedMessage: String) {
        val failure = assertFailsWith<InvocationTargetException> { abiType(type) }.targetException

        assertEquals(expectedMessage, failure.message)
    }

    private fun abiType(type: Type): Any = fromMethod.invoke(abiCompanion, type, callbackContext)

    private fun scalarShape(value: Any): ScalarShape = ScalarShape(
        kind = value.javaClass.getMethod("getKind").invoke(value).toString(),
        unsigned = value.javaClass.getMethod("getUnsigned").invoke(value) as Boolean,
    )

    private data class ScalarShape(val kind: String, val unsigned: Boolean)

    companion object {
        private val abiTypeClass =
            Class.forName("org.graphiks.kextract.kotlin.abi.KotlinKmpCAbiType")
        private val abiContextClass =
            Class.forName("org.graphiks.kextract.kotlin.abi.KotlinKmpAbiContext")
        private val abiCompanion = abiTypeClass.getField("Companion").get(null)
        private val callbackContext = abiContextClass.enumConstants.single { it.toString() == "CALLBACK" }
        private val fromMethod = abiCompanion.javaClass.getMethod("from", Type::class.java, abiContextClass)
    }
}
