package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Position
import org.graphiks.kextract.Type
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateFilterTest {
    private val intType = Type.primitive(Type.Primitive.Kind.Int)

    @Test
    fun `enum members and top-level constants use separate duplicate sets`() {
        val enumMember = constant("KxSharedName", 1)
        val macro = constant("KxSharedName", 2)
        val header = Declaration.toplevel(
            Position.NO_POSITION,
            Declaration.enum_(Position.NO_POSITION, "KxType", enumMember),
            macro,
        )

        DuplicateFilter().scan(header)

        assertFalse(isSkipped(enumMember))
        assertFalse(isSkipped(macro))
    }

    @Test
    fun `duplicate top-level constants are still suppressed`() {
        val first = constant("KxRepeatedMacro", 1)
        val duplicate = constant("KxRepeatedMacro", 2)

        DuplicateFilter().scan(Declaration.toplevel(Position.NO_POSITION, first, duplicate))

        assertFalse(isSkipped(first))
        assertTrue(isSkipped(duplicate))
    }

    @Test
    fun `duplicate enum members are still suppressed`() {
        val first = constant("KxRepeatedMember", 1)
        val duplicate = constant("KxRepeatedMember", 2)
        val enumDecl = Declaration.enum_(Position.NO_POSITION, "KxType", first, duplicate)

        DuplicateFilter().scan(Declaration.toplevel(Position.NO_POSITION, enumDecl))

        assertFalse(isSkipped(first))
        assertTrue(isSkipped(duplicate))
    }

    private fun constant(name: String, value: Int): Declaration.Constant =
        Declaration.constant(Position.NO_POSITION, name, value, intType)

    private fun isSkipped(declaration: Declaration): Boolean =
        declaration.attributes().any {
            it.javaClass.name == "org.graphiks.kextract.DeclarationImpl\$Skip"
        }
}
