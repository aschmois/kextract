package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Type
import org.junit.jupiter.api.Test
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
            KotlinCallbackCAbiType.Scalar(KotlinCallbackCAbiType.Scalar.Kind.I64, unsigned = false),
            KotlinCallbackCAbiType.from(Type.primitive(Type.Primitive.Kind.LongLong)),
        )
        assertEquals(
            KotlinCallbackCAbiType.Scalar(KotlinCallbackCAbiType.Scalar.Kind.I64, unsigned = true),
            KotlinCallbackCAbiType.from(
                Type.qualified(
                    Type.Delegated.Kind.UNSIGNED,
                    Type.primitive(Type.Primitive.Kind.LongLong),
                ),
            ),
        )
        assertEquals(
            KotlinCallbackCAbiType.Scalar(KotlinCallbackCAbiType.Scalar.Kind.F64, unsigned = false),
            KotlinCallbackCAbiType.from(Type.primitive(Type.Primitive.Kind.Double)),
        )
    }

    private fun assertUnsupportedVariableWidthScalar(type: Type, expectedMessage: String) {
        val failure = assertFailsWith<UnsupportedOperationException> {
            KotlinCallbackCAbiType.from(type)
        }

        assertEquals(expectedMessage, failure.message)
    }
}
