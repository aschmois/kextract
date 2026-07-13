package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.testsupport.GenerationRequest
import org.graphiks.kextract.testsupport.GeneratedSourceTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MacroParserIntegrationTest {

    @Test
    fun `macro parser materializes numeric and composed constants but ignores function-like macros`() {
        val parsed = GeneratedSourceTestSupport.parse(
            GenerationRequest(
                source = """
                    #define COUNT 42
                    #define MASK 0x10
                    #define TOTAL (COUNT + MASK)
                    #define ADD(left, right) ((left) + (right))
                """.trimIndent(),
            ),
        )
        val constants = parsed.members()
            .filterIsInstance<Declaration.Constant>()
            .filter { it.name() in setOf("COUNT", "MASK", "TOTAL", "ADD") }
            .associateBy { it.name() }

        assertEquals(42L, constants.getValue("COUNT").value())
        assertEquals(16L, constants.getValue("MASK").value())
        assertEquals(58L, constants.getValue("TOTAL").value())
        assertEquals(Type.Primitive.Kind.Int, (constants.getValue("COUNT").type() as Type.Primitive).kind())
        assertFalse(constants.containsKey("ADD"))
        assertTrue(constants.keys.containsAll(setOf("COUNT", "MASK", "TOTAL")))
    }
}
