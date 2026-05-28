package org.graphiks.kextract.integration

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.MacCondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.kotlin.KotlinGenerator
import org.graphiks.kextract.pipeline.KextractTool
import org.graphiks.kextract.pipeline.NameMangler
import java.nio.file.Files

/**
 * Regression tests for issue #22 — generator emits ValueLayout.ADDRESS for all return types.
 *
 * Each test documents a specific failure mode from the report:
 *   Bug 1 — scalar typedef return types (BOOL, CGFloat, NSInteger) get ADDRESS instead of the
 *            correct primitive layout, causing ClassCastException at runtime.
 *   Bug 4 — @JvmInline value-class / NS_ENUM arguments crash layoutFor() in ObjCRuntime.
 */
@EnabledIf(MacCondition::class)
class Issue22ReproductionTest : FreeSpec({

    fun generate(objcSource: String, pkg: String = "test"): String {
        val tmp = Files.createTempFile("kextract_issue22_", ".h")
        return try {
            tmp.toFile().writeText(objcSource)
            val headerName = tmp.fileName.toString()
            val parsed = KextractTool.parse(listOf(tmp.toString()), "-x", "objective-c")
            val mangled = NameMangler(headerName).scan(parsed)
            KotlinGenerator().generate(mangled, headerName, pkg)
                .joinToString("\n") { it.contents }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // ── Bug 1: scalar typedef return types ────────────────────────────────────

    /**
     * CGFloat is typedef double.
     * The method returning CGFloat must use ValueLayout.JAVA_DOUBLE, not ADDRESS.
     * Using ADDRESS causes `NativeMemorySegmentImpl cannot be cast to Double` at runtime.
     */
    "Bug 1a — CGFloat return type uses JAVA_DOUBLE, not ADDRESS" {
        val src = generate("""
            typedef double CGFloat;

            @interface KxWindow
            - (CGFloat)backingScaleFactor;
            @end
        """.trimIndent())

        src shouldContain "ValueLayout.JAVA_DOUBLE"
        src shouldNotContain "ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as CGFloat"
    }

    /**
     * NSInteger is typedef long.
     * The method must use ValueLayout.JAVA_LONG, not ADDRESS.
     */
    "Bug 1b — NSInteger return type uses JAVA_LONG, not ADDRESS" {
        val src = generate("""
            typedef long NSInteger;

            @interface KxView
            - (NSInteger)tag;
            @end
        """.trimIndent())

        src shouldContain "ValueLayout.JAVA_LONG"
        src shouldNotContain "ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as NSInteger"
    }

    /**
     * BOOL is typedef signed char on Apple platforms.
     * The method must use ValueLayout.JAVA_BYTE, not ADDRESS.
     */
    "Bug 1c — BOOL return type uses JAVA_BYTE, not ADDRESS" {
        val src = generate("""
            typedef signed char BOOL;

            @interface KxView
            - (BOOL)isOpaque;
            @end
        """.trimIndent())

        src shouldContain "ValueLayout.JAVA_BYTE"
        src shouldNotContain "ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as BOOL"
    }

    /**
     * Plain int return type must not use ADDRESS (this was already working but guard against regression).
     */
    "Bug 1d — plain int return type uses JAVA_INT" {
        val src = generate("""
            @interface KxCounter
            - (int)count;
            @end
        """.trimIndent())

        src shouldContain "ValueLayout.JAVA_INT"
        src shouldNotContain "ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as Int"
    }

    /**
     * NSUInteger is typedef unsigned long.
     * The method must use ValueLayout.JAVA_LONG (unsigned long = long on 64-bit).
     */
    "Bug 1e — NSUInteger (typedef unsigned long) return type uses JAVA_LONG" {
        val src = generate("""
            typedef unsigned long NSUInteger;

            @interface KxArray
            - (NSUInteger)count;
            @end
        """.trimIndent())

        src shouldContain "ValueLayout.JAVA_LONG"
        src shouldNotContain "ObjCRuntime.msgSend(ValueLayout.ADDRESS, ptr, sel) as NSUInteger"
    }

    // ── Bug 4: @JvmInline value class in ObjCRuntime.layoutFor() ─────────────

    /**
     * When an NS_OPTIONS value class (e.g. KxWindowStyleMask) is passed as a vararg Any,
     * ObjCRuntime.layoutFor() must handle it — today it throws IllegalArgumentException.
     *
     * The fix is either to unbox at the call site (generate `value.rawValue`) or
     * to extend layoutFor() with reflection-based rawValue lookup.
     * This test documents the call-site unboxing approach: the generated method body
     * must pass `value.rawValue` (the underlying Long), not `value` itself.
     */
    "Bug 4 — NS_OPTIONS (value class) argument is unboxed at call site" {
        val src = generate("""
            typedef enum : long {
                KxWindowStyleMaskTitled    = 1,
                KxWindowStyleMaskClosable  = 2
            } KxWindowStyleMask;

            @interface KxWindow
            - (void)setStyleMask:(KxWindowStyleMask)mask;
            @end
        """.trimIndent())

        // The setter should pass mask.rawValue (Long) rather than mask (value class)
        // so that ObjCRuntime.layoutFor() sees a Long and doesn't crash.
        src shouldContain "mask.rawValue"
    }
})
