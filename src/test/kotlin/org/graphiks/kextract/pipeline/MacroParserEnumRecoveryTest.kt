package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Position
import org.graphiks.kextract.Type
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class MacroParserEnumRecoveryTest {
    @TempDir
    private lateinit var tempDir: Path

    // Portable headers may reparse to a second valid Type.Declared; the helper tests below
    // cover the exact erroneous-type recovery path deterministically.
    @Test
    fun `PCH-backed macro survives the complete generation pipeline`() {
        val input = writeFixture()
        val output = tempDir.resolve("output")
        val errors = ByteArrayOutputStream()
        val logger = Logger(
            PrintWriter(ByteArrayOutputStream(), true),
            PrintWriter(errors, true),
        )

        val exitCode = KextractTool(logger).runGeneration(
            listOf(input.toString()),
            Options(
                clangArgs = parserOptions(),
                targetPackage = "test",
                outputDir = output.toString(),
            ),
        )

        assertEquals(KextractTool.SUCCESS, exitCode, errors.toString())
        val generated = output.toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertContains(generated, "KxAnyEventType(-1L)")
        assertContains(
            generated,
            "fun KxAnyEventType(): KxEventType = KxEventType.fromValue(-1L)",
        )
    }

    @Test
    fun `reparsed macro remains a declared named enum type`() {
        val input = writeFixture()

        val parsed = KextractTool.parse(listOf(input.toString()), *parserOptions().toTypedArray())
        val enumDeclaration = parsed.members()
            .filterIsInstance<Declaration.Scoped>()
            .single {
                it.kind() == Declaration.Scoped.Kind.ENUM && it.name() == "KxEventType"
            }
        val macro = parsed.members()
            .filterIsInstance<Declaration.Constant>()
            .single { it.name() == "KxAnyEventType" }

        val macroType = assertIs<Type.Declared>(macro.type())
        assertEquals(enumDeclaration.name(), macroType.tree().name())
        assertEquals(Declaration.Scoped.Kind.ENUM, macroType.tree().kind())
    }

    @Test
    fun `exact named enum error recovers the original enum declaration`() {
        val originalEnum = Declaration.enum_(Position.NO_POSITION, "KxEventType")
        val erroneousType = Type.error("enum KxEventType")

        val recovered = recoverReparsedEnumType(erroneousType) { kind, name ->
            assertEquals(Declaration.Scoped.Kind.ENUM, kind)
            assertEquals("KxEventType", name)
            originalEnum
        }

        val declared = assertIs<Type.Declared>(recovered)
        assertSame(originalEnum, declared.tree())
    }

    @Test
    fun `enum recovery preserves unsupported and already declared types`() {
        val originalEnum = Declaration.enum_(Position.NO_POSITION, "KxEventType")
        val alreadyDeclared = Type.declared(originalEnum)
        val missing = Type.error("enum KxMissing")
        val ambiguous = Type.error("enum KxAmbiguous")
        val unsupported = listOf(
            Type.error("struct KxEventType"),
            Type.error("enum "),
            Type.error("enum Kx-EventType"),
            Type.error("enum KxEventType extra"),
        )

        assertSame(alreadyDeclared, recoverWithoutLookup(alreadyDeclared))
        assertSame(missing, recoverReparsedEnumType(missing) { _, _ -> null })
        assertSame(ambiguous, recoverReparsedEnumType(ambiguous) { _, _ -> null })
        unsupported.forEach { type ->
            assertSame(type, recoverWithoutLookup(type))
        }
    }

    private fun writeFixture(): Path {
        tempDir.resolve("kx_types.h").writeText(
            """
                typedef enum : unsigned long {
                    KxEventNone = 0,
                    KxEventKnown = 1
                } KxEventType;
            """.trimIndent(),
        )
        return tempDir.resolve("input.h").also {
            it.writeText(
                """
                    #include <kx_types.h>
                    #define KxAnyEventType ((KxEventType)(~0))
                """.trimIndent(),
            )
        }
    }

    private fun parserOptions(): List<String> =
        listOf("-x", "objective-c", "-isystem", tempDir.toString())

    private fun recoverWithoutLookup(type: Type): Type =
        recoverReparsedEnumType(type) { _, _ ->
            error("lookup must not run for $type")
        }

    private fun recoverReparsedEnumType(
        type: Type,
        findUniqueScoped: (Declaration.Scoped.Kind, String) -> Declaration.Scoped?,
    ): Type {
        val macroParserClass = Class.forName("org.graphiks.kextract.pipeline.MacroParserImpl")
        val companion = macroParserClass.getDeclaredField("Companion").get(null)
        val recovery = companion.javaClass.declaredMethods.single {
            it.name.substringBefore('$') == "recoverReparsedEnumType" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Type::class.java &&
                it.parameterTypes[1] == Function2::class.java
        }
        recovery.isAccessible = true
        return recovery.invoke(companion, type, findUniqueScoped) as Type
    }
}
