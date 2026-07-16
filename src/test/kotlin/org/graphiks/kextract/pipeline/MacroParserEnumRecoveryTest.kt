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
import kotlin.test.assertNull
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
    fun `exact dollar enum error recovers the original enum declaration`() {
        val originalEnum = Declaration.enum_(Position.NO_POSITION, "Kx\$Dollar")
        val erroneousType = Type.error("enum Kx\$Dollar")

        val recovered = recoverReparsedEnumType(erroneousType) { kind, name ->
            assertEquals(Declaration.Scoped.Kind.ENUM, kind)
            assertEquals("Kx\$Dollar", name)
            originalEnum
        }

        val declared = assertIs<Type.Declared>(recovered)
        assertSame(originalEnum, declared.tree())
    }

    @Test
    fun `exact Unicode enum error recovers the original enum declaration`() {
        val originalEnum = Declaration.enum_(Position.NO_POSITION, "KxÉtat")
        val erroneousType = Type.error("enum KxÉtat")

        val recovered = recoverReparsedEnumType(erroneousType) { kind, name ->
            assertEquals(Declaration.Scoped.Kind.ENUM, kind)
            assertEquals("KxÉtat", name)
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
            Type.error("enum Kx EventType"),
            Type.error("enum (anonymous at fixture.h:1:1)"),
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

    @Test
    fun `recovery pass success resolves the original erroneous enum type`() {
        val originalEnum = Declaration.enum_(Position.NO_POSITION, "KxRecoveryType")
        val recoveredType = recoveryPassSuccessType(
            Type.error("enum KxRecoveryType"),
            originalEnum,
        )

        val declared = assertIs<Type.Declared>(recoveredType)
        assertSame(originalEnum, declared.tree())
    }

    @Test
    fun `recovery pass success preserves non-enum missing and ambiguous original types`() {
        val nonEnum = Type.error("struct KxRecoveryType")
        val missing = Type.error("enum KxMissingRecoveryType")
        val ambiguous = Type.error("enum KxAmbiguousRecoveryType")
        val firstAmbiguous = Declaration.enum_(Position.NO_POSITION, "KxAmbiguousRecoveryType")
        val secondAmbiguous = Declaration.enum_(Position.NO_POSITION, "KxAmbiguousRecoveryType")

        assertSame(nonEnum, recoveryPassSuccessType(nonEnum))
        assertSame(missing, recoveryPassSuccessType(missing))
        assertSame(
            ambiguous,
            recoveryPassSuccessType(ambiguous, firstAmbiguous, secondAmbiguous),
        )
    }

    @Test
    fun `scoped lookup requires referentially unique declarations`() {
        val enumDeclaration = StructurallyComparableEnum("KxIdentityType")
        val structurallyEqualDeclaration = StructurallyComparableEnum("KxIdentityType")

        assertEquals(enumDeclaration, structurallyEqualDeclaration)
        assertSame(enumDeclaration, findUniqueScoped(enumDeclaration))
        assertSame(enumDeclaration, findUniqueScoped(enumDeclaration, enumDeclaration))
        assertNull(findUniqueScoped(enumDeclaration, structurallyEqualDeclaration))
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

    private fun recoveryPassSuccessType(
        originalType: Type,
        vararg declarations: Declaration.Scoped,
    ): Type {
        val treeMaker = treeMakerWith(*declarations)
        val macroParserClass = Class.forName("org.graphiks.kextract.pipeline.MacroParserImpl")
        val constructor = macroParserClass.declaredConstructors.single {
            it.parameterCount == 3
        }
        constructor.isAccessible = true
        val parser = constructor.newInstance(null, treeMaker, Logger.DEFAULT)
        val table = macroParserClass.getMethod("getMacroTable").invoke(parser)
        table.javaClass.getMethod(
            "enterMacro",
            String::class.java,
            Array<String>::class.java,
            Position::class.java,
        ).invoke(
            table,
            "KxRecoveryOnly",
            arrayOf("KxRecoveryOnly"),
            Position.NO_POSITION,
        )

        @Suppress("UNCHECKED_CAST")
        val entries = table.javaClass.getMethod("getMacrosByMangledName").invoke(table)
            as MutableMap<String, Any>
        val unparsed = entries.values.single()
        val recoverable = unparsed.javaClass.getMethod("failure", Type::class.java)
            .invoke(unparsed, originalType)
        val success = recoverable.javaClass.getMethod(
            "success",
            Type::class.java,
            Any::class.java,
        ).invoke(
            recoverable,
            Type.primitive(Type.Primitive.Kind.Long),
            7L,
        )
        val constant = success.javaClass.getMethod("constant").invoke(success) as Declaration.Constant
        return constant.type()
    }

    private fun treeMakerWith(vararg declarations: Declaration.Scoped): Any {
        val treeMakerClass = Class.forName("org.graphiks.kextract.pipeline.TreeMaker")
        val constructor = treeMakerClass.declaredConstructors.single { it.parameterCount == 0 }
        constructor.isAccessible = true
        val treeMaker = constructor.newInstance()
        val cacheField = treeMakerClass.declaredFields.single {
            it.name == "declarationCacheNew" && Map::class.java.isAssignableFrom(it.type)
        }
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(treeMaker) as MutableMap<Any, Declaration>
        declarations.forEachIndexed { index, declaration ->
            cache["test-key-$index"] = declaration
        }
        return treeMaker
    }

    private fun findUniqueScoped(vararg declarations: Declaration.Scoped): Declaration.Scoped? {
        val treeMakerClass = Class.forName("org.graphiks.kextract.pipeline.TreeMaker")
        val constructor = treeMakerClass.declaredConstructors.single { it.parameterCount == 0 }
        constructor.isAccessible = true
        val treeMaker = constructor.newInstance()

        val cacheField = treeMakerClass.declaredFields.single {
            it.name == "declarationCacheNew" && Map::class.java.isAssignableFrom(it.type)
        }
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(treeMaker) as MutableMap<Any, Declaration>
        declarations.forEachIndexed { index, declaration ->
            cache["test-key-$index"] = declaration
        }

        val lookup = treeMakerClass.declaredMethods.single {
            it.name == "findUniqueScoped" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Declaration.Scoped.Kind::class.java &&
                it.parameterTypes[1] == String::class.java
        }
        lookup.isAccessible = true
        return lookup.invoke(
            treeMaker,
            Declaration.Scoped.Kind.ENUM,
            "KxIdentityType",
        ) as Declaration.Scoped?
    }

    private class StructurallyComparableEnum(
        private val enumName: String,
    ) : Declaration.Scoped {
        override fun pos(): Position = Position.NO_POSITION
        override fun name(): String = enumName
        override fun members(): List<Declaration> = emptyList()
        override fun kind(): Declaration.Scoped.Kind = Declaration.Scoped.Kind.ENUM
        override fun <R> accept(visitor: Declaration.Visitor<R>): R = visitor.visitScoped(this)
        override fun attributes(): Collection<Declaration.Attribute> = emptyList()
        override fun <R : Declaration.Attribute> getAttribute(attributeClass: Class<R>): R? = null
        override fun <R : Declaration.Attribute> addAttribute(attribute: R) =
            error("attributes are not supported by this test declaration")

        override fun equals(other: Any?): Boolean =
            other is StructurallyComparableEnum && enumName == other.enumName

        override fun hashCode(): Int = enumName.hashCode()
    }
}
