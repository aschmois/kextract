package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Position
import org.graphiks.kextract.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintWriter

class FilterTest {

    private val pos = Position.NO_POSITION

    @Test
    fun `include filter keeps the requested declaration kind and skips unrelated declarations`() {
        val cases = listOf(
            IncludeHelper.IncludeKind.CONSTANT to "COUNT",
            IncludeHelper.IncludeKind.VAR to "globalValue",
            IncludeHelper.IncludeKind.FUNCTION to "add",
            IncludeHelper.IncludeKind.TYPEDEF to "PointAlias",
            IncludeHelper.IncludeKind.STRUCT to "Point",
            IncludeHelper.IncludeKind.UNION to "Value",
            IncludeHelper.IncludeKind.OBJC_CLASS to "Widget",
            IncludeHelper.IncludeKind.OBJC_PROTOCOL to "Renderable",
            IncludeHelper.IncludeKind.OBJC_CATEGORY to "Widget+Tracing",
        )

        cases.forEach { (kind, selectedName) ->
            val declarations = modelDeclarations()
            val helper = IncludeHelper().also { it.addSymbol(kind, selectedName) }

            IncludeFilter(helper).scan(Declaration.toplevel(pos, *declarations.values.toTypedArray()))

            declarations.forEach { (name, declaration) ->
                if (name == selectedName) {
                    assertFalse(isSkipped(declaration), "$kind should retain $name")
                } else {
                    assertTrue(isSkipped(declaration), "$kind should skip unrelated $name")
                }
            }
        }
    }

    @Test
    fun `include filter preserves selected declarations and their selected type dependencies`() {
        val point = Declaration.struct(pos, "Point", Declaration.field(pos, "x", Type.primitive(Type.Primitive.Kind.Int)))
        val alias = Declaration.typedef(pos, "PointAlias", Type.declared(point))
        val parameter = Declaration.parameter(pos, "point", Type.declared(point))
        val usePoint = Declaration.function(
            pos,
            "usePoint",
            Type.function(false, Type.void_(), Type.declared(point)),
            parameter,
        )
        val unrelated = Declaration.function(pos, "unrelated", Type.function(false, Type.void_()))
        val header = Declaration.toplevel(pos, point, alias, usePoint, unrelated)
        val helper = IncludeHelper().also {
            it.addSymbol(IncludeHelper.IncludeKind.STRUCT, "Point")
            it.addSymbol(IncludeHelper.IncludeKind.TYPEDEF, "PointAlias")
            it.addSymbol(IncludeHelper.IncludeKind.FUNCTION, "usePoint")
        }

        IncludeFilter(helper).scan(header)

        assertFalse(isSkipped(point))
        assertFalse(isSkipped(alias))
        assertFalse(isSkipped(usePoint))
        assertTrue(isSkipped(unrelated))
    }

    @Test
    fun `empty include configuration retains every declaration`() {
        val declarations = modelDeclarations()

        IncludeFilter(IncludeHelper()).scan(Declaration.toplevel(pos, *declarations.values.toTypedArray()))

        declarations.values.forEach { assertFalse(isSkipped(it), "${it.name()} should be retained") }
    }

    @Test
    fun `duplicate filter skips only later declarations with the same identity`() {
        val first = Declaration.function(pos, "duplicate", Type.function(false, Type.void_()))
        val second = Declaration.function(pos, "duplicate", Type.function(false, Type.void_()))

        DuplicateFilter().scan(Declaration.toplevel(pos, first, second))

        assertFalse(isSkipped(first))
        assertTrue(isSkipped(second))
    }

    @Test
    fun `unsupported filter marks unsupported declarations and reports a verbose warning`() {
        val savedVerbose = KextractConfig.verbose
        val errors = ByteArrayOutputStream()
        try {
            KextractConfig.verbose = true
            val logger = Logger(PrintWriter(ByteArrayOutputStream()), PrintWriter(errors, true))
            val unsupported = Declaration.function(
                pos,
                "usesLongDouble",
                Type.function(false, Type.primitive(Type.Primitive.Kind.LongDouble)),
            )

            UnsupportedFilter(logger).scan(Declaration.toplevel(pos, unsupported))

            assertTrue(isSkipped(unsupported))
            assertTrue(errors.toString().contains("Skipping usesLongDouble"))
            assertTrue(errors.toString().contains("type LongDouble is not supported"), errors.toString())
        } finally {
            KextractConfig.verbose = savedVerbose
        }
    }

    @Test
    fun `missing dependency is reported in both verbose modes`() {
        val savedVerbose = KextractConfig.verbose
        try {
            listOf(false, true).forEach { verbose ->
                KextractConfig.verbose = verbose
                val skippedStruct = Declaration.struct(pos, "MissingPoint")
                markAsSkip(skippedStruct)
                val parameter = Declaration.parameter(pos, "point", Type.declared(skippedStruct))
                val function = Declaration.function(
                    pos,
                    "useMissingPoint",
                    Type.function(false, Type.void_(), Type.declared(skippedStruct)),
                    parameter,
                )
                val logger = Logger(PrintWriter(ByteArrayOutputStream()), PrintWriter(ByteArrayOutputStream(), true))

                MissingDepChecker(logger).scan(Declaration.toplevel(pos, skippedStruct, function))

                assertTrue(logger.hasErrors(), "missing dependency must be reported with verbose=$verbose")
            }
        } finally {
            KextractConfig.verbose = savedVerbose
        }
    }

    private fun modelDeclarations(): LinkedHashMap<String, Declaration> {
        val point = Declaration.struct(pos, "Point", Declaration.field(pos, "x", Type.primitive(Type.Primitive.Kind.Int)))
        val value = Declaration.union(pos, "Value", Declaration.field(pos, "i", Type.primitive(Type.Primitive.Kind.Int)))
        return linkedMapOf(
            "COUNT" to Declaration.constant(pos, "COUNT", 42L, Type.primitive(Type.Primitive.Kind.Int)),
            "globalValue" to Declaration.globalVariable(pos, "globalValue", Type.primitive(Type.Primitive.Kind.Int)),
            "add" to Declaration.function(pos, "add", Type.function(false, Type.primitive(Type.Primitive.Kind.Int))),
            "PointAlias" to Declaration.typedef(pos, "PointAlias", Type.declared(point)),
            "Point" to point,
            "Value" to value,
            "Widget" to Declaration.objcClass(pos, "Widget", null, emptyList(), emptyList(), emptyList()),
            "Renderable" to Declaration.objcProtocol(pos, "Renderable", emptyList(), emptyList(), emptyList()),
            "Widget+Tracing" to Declaration.objcCategory(
                pos, "Widget+Tracing", "Widget", "Tracing", emptyList(), emptyList()
            ),
        )
    }

    private fun markAsSkip(declaration: Declaration) {
        val skipClass = Class.forName("org.graphiks.kextract.DeclarationImpl\$Skip")
        val instance = skipClass.getDeclaredField("INSTANCE").get(null)
        skipClass.getDeclaredMethod("with", Declaration::class.java).invoke(instance, declaration)
    }

    private fun isSkipped(declaration: Declaration): Boolean {
        val skipClass = Class.forName("org.graphiks.kextract.DeclarationImpl\$Skip")
        val instance = skipClass.getDeclaredField("INSTANCE").get(null)
        return skipClass.getDeclaredMethod("isPresent", Declaration::class.java)
            .invoke(instance, declaration) as Boolean
    }
}
