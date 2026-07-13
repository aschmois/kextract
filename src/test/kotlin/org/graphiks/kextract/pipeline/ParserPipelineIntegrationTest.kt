package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.testsupport.GenerationRequest
import org.graphiks.kextract.testsupport.GeneratedSourceTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintWriter

class ParserPipelineIntegrationTest {

    @Test
    fun `parser builds records arrays pointers unions enums nested declarations and callbacks`() {
        val parsed = GeneratedSourceTestSupport.parse(
            GenerationRequest(
                source = """
                    struct Sample {
                        struct Nested { int nested; } named;
                        union { int integer; float real; };
                        int values[4];
                        const char *name;
                        unsigned flags:3;
                    };
                    union Number { int integer; double real; };
                    enum Color { RED = 1, BLUE = 2 };
                    typedef int (*Callback)(const char *message, int code);
                    int invoke(Callback callback, ...);
                """.trimIndent(),
            ),
        )

        val sample = parsed.members().single { it.name() == "Sample" } as Declaration.Scoped
        assertEquals(Declaration.Scoped.Kind.STRUCT, sample.kind())
        assertEquals(1, sample.pos().line)
        assertEquals(8, sample.pos().col)

        val values = sample.members().single { it.name() == "values" } as Declaration.Variable
        val array = values.type() as Type.Array
        assertEquals(Type.Array.Kind.ARRAY, array.kind())
        assertEquals(4L, array.elementCount())
        assertEquals(Type.Primitive.Kind.Int, (array.elementType() as Type.Primitive).kind())

        val name = sample.members().single { it.name() == "name" } as Declaration.Variable
        assertEquals(Type.Delegated.Kind.POINTER, (name.type() as Type.Delegated).kind())
        val nestedField = sample.members().single { it.name() == "named" } as Declaration.Variable
        val nested = (nestedField.type() as Type.Declared).tree()
        assertEquals("Nested", nested.name())
        assertEquals(Declaration.Scoped.Kind.STRUCT, nested.kind())
        val nestedValue = nested.members().single { it.name() == "nested" } as Declaration.Variable
        assertEquals(Type.Primitive.Kind.Int, (nestedValue.type() as Type.Primitive).kind())
        assertTrue(sample.members().any { it is Declaration.Scoped && it.kind() == Declaration.Scoped.Kind.UNION })
        val bitfields = sample.members().single { it is Declaration.Scoped && it.kind() == Declaration.Scoped.Kind.BITFIELDS }
            as Declaration.Scoped
        assertEquals(3L, (bitfields.members().single() as Declaration.Bitfield).width())

        val number = parsed.members().single { it.name() == "Number" } as Declaration.Scoped
        assertEquals(Declaration.Scoped.Kind.UNION, number.kind())
        val color = parsed.members().single { it.name() == "Color" } as Declaration.Scoped
        assertEquals(Declaration.Scoped.Kind.ENUM, color.kind())
        assertEquals(1L, (color.members().single { it.name() == "RED" } as Declaration.Constant).value())

        val callback = parsed.members().single { it.name() == "Callback" } as Declaration.Typedef
        val callbackPointer = callback.type() as Type.Delegated
        assertEquals(Type.Delegated.Kind.POINTER, callbackPointer.kind())
        assertTrue(callbackPointer.type() is Type.Function)

        val invoke = parsed.members().single { it.name() == "invoke" } as Declaration.Function
        assertTrue(invoke.type().varargs())
        assertEquals(1, invoke.parameters().size)
        val callbackAlias = invoke.parameters().single().type() as Type.Delegated
        assertEquals(Type.Delegated.Kind.TYPEDEF, callbackAlias.kind())
        val callbackParameter = callbackAlias.type() as Type.Delegated
        assertEquals(Type.Delegated.Kind.POINTER, callbackParameter.kind())
        assertTrue(callbackParameter.type() is Type.Function)
    }

    @Test
    fun `parser surfaces clang diagnostics for an invalid header`() {
        val errors = ByteArrayOutputStream()
        val logger = Logger(PrintWriter(ByteArrayOutputStream()), PrintWriter(errors, true))

        assertDoesNotThrow<Declaration.Scoped> {
            Parser(logger).parse("invalid.h", "struct Broken {", emptyList())
        }

        assertTrue(logger.hasClangErrors())
        assertTrue(errors.toString().contains("error:"), errors.toString())
    }
}
