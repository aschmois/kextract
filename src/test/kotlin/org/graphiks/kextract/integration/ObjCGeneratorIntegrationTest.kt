package org.graphiks.kextract.integration

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.MacCondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graphiks.kextract.pipeline.NameMangler
import org.graphiks.kextract.kotlin.KotlinGenerator
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.pipeline.KextractTool
import java.nio.file.Files

/**
 * Integration tests for Objective-C → Kotlin/JVM binding generation.
 *
 * Only run on macOS:
 * - libclang's ObjC cursor kinds require the ObjC runtime headers
 * - The generated ObjCRuntime.kt references libobjc.dylib
 *
 * We use custom class/protocol names (not NS-prefixed) to avoid conflicts
 * with the ObjC runtime headers that clang auto-includes in ObjC mode.
 * We also omit `-fobjc-arc` to keep the runtime surface minimal.
 */
@EnabledIf(MacCondition::class)
class ObjCGeneratorIntegrationTest : FreeSpec({

    /**
     * Parse inline ObjC source and run the full generator pipeline.
     * Returns all generated [KotlinSourceFile] objects.
     */
    fun generateAll(objcSource: String, pkg: String = "test"): List<KotlinSourceFile> {
        val tmp = Files.createTempFile("kextract_objc_test_", ".h")
        return try {
            tmp.toFile().writeText(objcSource)
            val headerName = tmp.fileName.toString()
            val parsed = KextractTool.parse(
                listOf(tmp.toString()),
                "-x", "objective-c"
            )
            val mangled = NameMangler(headerName).scan(parsed)
            KotlinGenerator().generate(mangled, headerName, pkg)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Concatenates all generated source file contents. */
    fun generate(objcSource: String, pkg: String = "test"): String =
        generateAll(objcSource, pkg).joinToString("\n") { it.contents }

    /** Returns the ObjCRuntime.kt content if present, else "". */
    fun getRuntime(files: List<KotlinSourceFile>): String =
        files.firstOrNull { it.className == "ObjCRuntime" }?.contents ?: ""

    // ── @interface (class) ───────────────────────────────────────────────────

    "ObjC class wrapper" - {
        // Use a custom root class (no superclass) to avoid NS-prefix runtime conflicts
        val src = generate("""
            @interface KxBaseObject
            - (long)count;
            + (instancetype)newInstance;
            @end
        """.trimIndent())

        "generates open class with MemorySegment ptr" {
            src shouldContain "open class KxBaseObject"
            src shouldContain "val ptr: MemorySegment"
        }

        "no supertype expression for root class" {
            src shouldNotContain ": KxBaseObject(ptr)"
        }

        "companion object with lazy _class" {
            src shouldContain "companion object"
            src shouldContain """private val _class: MemorySegment by lazy { ObjCRuntime.getClass("KxBaseObject") }"""
        }

        "class method in companion" {
            src shouldContain "fun newInstance()"
            src shouldContain "_class"
        }

        "instance method uses ObjCRuntime.msgSend" {
            src shouldContain "fun count()"
            src shouldContain "ObjCRuntime.msgSend"
        }
    }

    "ObjC class with superclass" - {
        // Define base first so clang sees the definition, then sub
        val src = generate("""
            @interface KxBase
            @end
            @interface KxDerived : KxBase
            - (void)doWork;
            @end
        """.trimIndent())

        "derived class extends base with ptr forwarding" {
            src shouldContain "open class KxDerived"
            src shouldContain ": KxBase(ptr)"
        }
    }

    // ── @protocol ────────────────────────────────────────────────────────────

    "ObjC protocol interface" - {
        val src = generate("""
            @protocol KxCopyable
            - (id)copy;
            @end
        """.trimIndent())

        "generates interface" {
            src shouldContain "interface KxCopyable"
        }

        "required method is abstract (no default body)" {
            src shouldContain "fun copy()"
            // Required methods have no '= throw' body
            src shouldNotContain "throw UnsupportedOperationException"
        }
    }

    "ObjC protocol optional method" - {
        val src = generate("""
            @protocol KxOptional
            @optional
            - (void)optionalWork;
            @end
        """.trimIndent())

        "optional method has default throw body" {
            src shouldContain "// @optional"
            src shouldContain "throw UnsupportedOperationException"
            src shouldContain "optionalWork"
        }
    }

    // ── ObjCRuntime.kt emission ──────────────────────────────────────────────

    "ObjCRuntime helper file is emitted when ObjC declarations present" - {
        val files = generateAll("""
            @interface KxCounter
            - (long)count;
            @end
        """.trimIndent())

        "at least two files are generated (bindings + ObjCRuntime)" {
            files shouldHaveAtLeastSize 2
        }

        "ObjCRuntime.kt is among the output files" {
            val runtime = getRuntime(files)
            runtime shouldContain "object ObjCRuntime"
            runtime shouldContain "objc_msgSend"
            runtime shouldContain "fun sel(name: String)"
            runtime shouldContain "fun getClass(name: String)"
            runtime shouldContain "fun msgSend("
        }

        "ObjCRuntime carries the correct package" {
            val runtime = getRuntime(files)
            runtime shouldContain "package test"
        }
    }

    "ObjCRuntime is NOT emitted for pure C headers" - {
        val tmpC = Files.createTempFile("kextract_c_test_", ".h")
        val files: List<KotlinSourceFile> = try {
            tmpC.toFile().writeText("int add(int a, int b);")
            val parsed = KextractTool.parse(listOf(tmpC.toString()))
            val mangled = NameMangler(tmpC.fileName.toString()).scan(parsed)
            KotlinGenerator().generate(mangled, tmpC.fileName.toString(), "test")
        } finally {
            Files.deleteIfExists(tmpC)
        }

        "no ObjCRuntime in pure-C output" {
            getRuntime(files) shouldBe ""
        }
    }

    // ── @property ────────────────────────────────────────────────────────────

    "ObjC property getter and setter" - {
        val src = generate("""
            @interface KxMutable
            @property (readwrite) long capacity;
            @end
        """.trimIndent())

        "getter function emitted" {
            src shouldContain "fun capacity()"
        }

        "setter function emitted for readwrite property" {
            src shouldContain "fun setCapacity"
        }
    }

    "ObjC readonly property has no setter" - {
        val src = generate("""
            @interface KxReadonly
            @property (readonly) long length;
            @end
        """.trimIndent())

        "getter emitted" {
            src shouldContain "fun length()"
        }

        "no setter emitted" {
            src shouldNotContain "fun setLength"
        }
    }

    // ── ObjC block pointer parameters ───────────────────────────────────────

    "ObjC block parameter maps to MemorySegment" - {
        // `void (^completion)(long)` is a BlockPointer type in libclang (TypeKind 113).
        // It must not fall through to `Any` — it should map to MemorySegment.
        val src = generate("""
            @interface KxAsync
            - (void)fetchWithCompletion:(void (^)(long result))completion;
            @end
        """.trimIndent())

        "block parameter type is MemorySegment, not Any" {
            src shouldContain "completion: MemorySegment"
            src shouldNotContain "completion: Any"
        }
    }

    // ── Selector → Kotlin name mapping ───────────────────────────────────────

    "selector name conversion" - {
        val src = generate("""
            @interface KxSelector
            - (void)withArg:(long)x andArg:(long)y;
            @end
        """.trimIndent())

        "colons in selector become underscores, trailing stripped" {
            // selector "withArg:andArg:" → kotlin name "withArg_andArg"
            src shouldContain "fun withArg_andArg"
        }
    }

    // ── NSString convenience overloads ────────────────────────────────────────

    "NSString return-type convenience overload" - {
        // NSString is a system class; use it as a return type on a custom class method
        val src = generate("""
            #include <Foundation/Foundation.h>
            @interface KxNSStringReturn
            - (NSString *)greeting;
            @end
        """.trimIndent())

        "raw MemorySegment method is emitted" {
            src shouldContain "fun greeting(): MemorySegment"
        }

        "String convenience overload is emitted" {
            src shouldContain "fun greetingAsString(): String = ObjCRuntime.toJavaString(greeting())"
        }
    }

    "NSString parameter convenience overload" - {
        val src = generate("""
            #include <Foundation/Foundation.h>
            @interface KxNSStringParam
            - (void)setTitle:(NSString *)title;
            @end
        """.trimIndent())

        "raw MemorySegment overload is present" {
            src shouldContain "fun setTitle(title: MemorySegment)"
        }

        "String convenience overload is emitted" {
            src shouldContain "fun setTitle(title: String)"
            src shouldContain "ObjCRuntime.newNSString(Arena.global(), title)"
        }
    }

    "NSString readwrite property convenience overloads" - {
        val src = generate("""
            #include <Foundation/Foundation.h>
            @interface KxNSStringProp
            @property (readwrite) NSString *label;
            @end
        """.trimIndent())

        "raw getter is emitted" {
            src shouldContain "fun label(): MemorySegment"
        }

        "String getter overload is emitted" {
            src shouldContain "fun labelAsString(): String = ObjCRuntime.toJavaString(label())"
        }

        "String setter overload is emitted" {
            src shouldContain "fun setLabel(value: String) = setLabel(ObjCRuntime.newNSString(Arena.global(), value))"
        }
    }

    "NSString readonly property has only getter overload" - {
        val src = generate("""
            #include <Foundation/Foundation.h>
            @interface KxNSStringReadonly
            @property (readonly) NSString *title;
            @end
        """.trimIndent())

        "getter overload is emitted" {
            src shouldContain "fun titleAsString(): String = ObjCRuntime.toJavaString(title())"
        }

        "no setter overload emitted" {
            src shouldNotContain "fun setTitle(value: String)"
        }
    }

    // ── @category ────────────────────────────────────────────────────────────

    "ObjC category instance method becomes extension function" - {
        val src = generate("""
            @interface KxBase
            @end
            @interface KxBase (KxExtras)
            - (long)computedValue;
            @end
        """.trimIndent())

        "extension function on the extended class" {
            src shouldContain "fun KxBase.computedValue()"
        }

        "message sent to ptr" {
            src shouldContain "ObjCRuntime.msgSend"
            src shouldContain "ptr"
        }

        "no top-level function emitted for instance method" {
            src shouldNotContain "fun KxBase_computedValue"
        }
    }

    "ObjC category class method becomes top-level function" - {
        val src = generate("""
            @interface KxFactory
            @end
            @interface KxFactory (KxCreation)
            + (instancetype)createWithValue:(long)v;
            @end
        """.trimIndent())

        "top-level function named <Class>_<method>" {
            src shouldContain "fun KxFactory_createWithValue("
        }

        "function calls ObjCRuntime.getClass directly" {
            src shouldContain """ObjCRuntime.getClass("KxFactory")"""
        }

        "no extension function on the class for class method" {
            src shouldNotContain "fun KxFactory.createWithValue"
        }

        "comment identifies it as a class method" {
            src shouldContain "// Class method: +[KxFactory createWithValue:]"
        }
    }

    "ObjC category with both instance and class methods" - {
        val src = generate("""
            @interface KxMixed
            @end
            @interface KxMixed (KxBoth)
            - (void)doWork;
            + (instancetype)make;
            @end
        """.trimIndent())

        "instance method is extension function" {
            src shouldContain "fun KxMixed.doWork()"
        }

        "class method is top-level function" {
            src shouldContain "fun KxMixed_make()"
        }

        "class method uses getClass, instance method uses ptr" {
            src shouldContain """ObjCRuntime.getClass("KxMixed")"""
            src shouldContain "ptr"

    "NS_ENUM generates Kotlin enum class" - {
        // Use a typed C enum (typedef enum : long { ... }) which is exactly what NS_ENUM expands
        // to after macro expansion.
        val src = generate("""
            typedef enum : long {
                KxOrderAscending  = -1,
                KxOrderSame       = 0,
                KxOrderDescending = 1
            } KxComparisonResult;
        """.trimIndent())

        "enum class is emitted instead of typealias" {
            src shouldContain "enum class KxComparisonResult"
            src shouldNotContain "typealias KxComparisonResult"
        }

        "enum entries carry Long values" {
            src shouldContain "KxOrderAscending(-1L)"
            src shouldContain "KxOrderSame(0L)"
            src shouldContain "KxOrderDescending(1L)"
        }

        "companion object with fromValue factory" {
            src shouldContain "companion object"
            src shouldContain "fun fromValue(v: Long): KxComparisonResult"
        }

        "enum constants not emitted as standalone top-level functions" {
            src shouldNotContain "fun KxOrderAscending()"
        }
    }

    "NS_OPTIONS generates @JvmInline value class" - {
        val src = generate("""
            typedef enum : long {
                KxNone              = 0,
                KxCaseInsensitive   = 1,
                KxLiteral           = 2,
                KxBackwards         = 4
            } KxStringCompareOptions;
        """.trimIndent())

        "value class with rawValue is emitted" {
            src shouldContain "@JvmInline"
            src shouldContain "value class KxStringCompareOptions(val rawValue: Long)"
        }

        "constants are in companion object" {
            src shouldContain "companion object"
            src shouldContain "val KxNone = KxStringCompareOptions(0L)"
            src shouldContain "val KxCaseInsensitive = KxStringCompareOptions(1L)"
        }

        "bit-ops are emitted" {
            src shouldContain "operator fun plus(o: KxStringCompareOptions)"
            src shouldContain "operator fun contains(o: KxStringCompareOptions)"
        }

        "no typealias for options type" {
            src shouldNotContain "typealias KxStringCompareOptions"
        }
    }

    "Enum typedef with Flags suffix generates value class" - {
        val src = generate("""
            typedef enum : long {
                KxEventNone  = 0,
                KxEventClick = 1,
                KxEventHover = 2
            } KxEventFlags;
        """.trimIndent())

        "value class emitted for Flags suffix" {
            src shouldContain "@JvmInline"
            src shouldContain "value class KxEventFlags(val rawValue: Long)"
        }
    }
})
