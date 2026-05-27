package org.graphiks.kextract.pipeline

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Position
import org.graphiks.kextract.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintWriter

/**
 * Unit tests for [MissingDepChecker].
 *
 * Key regression (GRA-90): [logger.err] was accidentally wrapped inside
 * `if (KextractConfig.verbose)`, so bad-include errors were silently
 * suppressed in the default (non-verbose) mode, allowing corrupt bindings
 * to be generated without any error signal.
 *
 * [DeclarationImpl.Skip] is `internal` to the kmain module, so we use
 * JVM reflection to mark declarations as Skip in these tests (the `internal`
 * modifier is a Kotlin compile-time gate; the JVM INSTANCE field is public).
 */
class MissingDepCheckerTest {

    private val pos = Position.NO_POSITION

    /** Marks [decl] as Skip via reflection (DeclarationImpl.Skip is internal to kmain). */
    private fun markAsSkip(decl: Declaration) {
        val skipClass = Class.forName("org.graphiks.kextract.DeclarationImpl\$Skip")
        val instance  = skipClass.getDeclaredField("INSTANCE").get(null)
        val withMethod = skipClass.getDeclaredMethod("with", Declaration::class.java)
        withMethod.invoke(instance, decl)
    }

    /** Returns a fresh Logger that captures stderr. */
    private fun makeLogger(): Pair<Logger, ByteArrayOutputStream> {
        val err = ByteArrayOutputStream()
        return Logger(PrintWriter(ByteArrayOutputStream()), PrintWriter(err, true)) to err
    }

    /** Builds a TOPLEVEL scope containing a function whose param type is [structDecl]. */
    private fun headerWith(structDecl: Declaration.Scoped): Declaration.Scoped {
        val paramType = Type.declared(structDecl)
        val param     = Declaration.parameter(pos, "x", paramType)
        val fnType    = Type.function(false, Type.void_(), paramType)
        val fn        = Declaration.function(pos, "useIt", fnType, param)
        return Declaration.toplevel(pos, structDecl, fn)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `error is reported when verbose is false (GRA-90 regression)`() {
        val saved = KextractConfig.verbose
        try {
            KextractConfig.verbose = false

            val skipStruct = Declaration.struct(pos, "OpaqueType")
            markAsSkip(skipStruct)

            val (logger, _) = makeLogger()
            MissingDepChecker(logger).scan(headerWith(skipStruct))

            assertTrue(logger.hasErrors(),
                "MissingDepChecker must report errors even when KextractConfig.verbose = false")
            assertTrue(logger.nErrors >= 1)
        } finally {
            KextractConfig.verbose = saved
        }
    }

    @Test
    fun `error is also reported when verbose is true`() {
        val saved = KextractConfig.verbose
        try {
            KextractConfig.verbose = true

            val skipStruct = Declaration.struct(pos, "OpaqueType")
            markAsSkip(skipStruct)

            val (logger, _) = makeLogger()
            MissingDepChecker(logger).scan(headerWith(skipStruct))

            assertTrue(logger.hasErrors())
            assertTrue(logger.nErrors >= 1)
        } finally {
            KextractConfig.verbose = saved
        }
    }

    @Test
    fun `no error when referenced struct is not Skip-marked`() {
        val (logger, _) = makeLogger()
        val normalStruct = Declaration.struct(pos, "NormalType")
        // not marked as Skip → no missing dep
        MissingDepChecker(logger).scan(headerWith(normalStruct))

        assertEquals(0, logger.nErrors, "No error expected for a non-Skip struct")
    }
}
