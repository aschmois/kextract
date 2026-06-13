// src/main/kotlin/org/openjdk/kextract/kotlin/builders/KotlinToplevelBuilder.kt
package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.kotlin.models.KotlinSourceFile
import org.graphiks.kextract.kotlin.utils.KotlinNameMangler
import org.graphiks.kextract.pipeline.Options

/**
 * Top-level builder for Kotlin files.
 * Coordinates generation of all declarations (structs, functions, ObjC classes, etc.).
 */
class KotlinToplevelBuilder(
    private val targetPackage: String,
    val className: String,
    private val headerName: String,
    private val libraries: List<Options.Library> = emptyList(),
    private val useSystemLoadLibrary: Boolean = false,
    private val splitOutput: Boolean = false
) : Declaration.Visitor<Unit> {
    private val slots = LinkedHashMap<String, SourceBuilder>()
    private val files = mutableListOf<KotlinSourceFile>()

    /** Base name derived from header filename (e.g. "AppKit" from "AppKit_h"). */
    private val headerBaseName: String = className.removeSuffix("_h")

    private val headerBuilder get() = KotlinHeaderBuilder(mainSlot, this)
    private val structBuilder get() = KotlinStructBuilder(mainSlot, this)
    private val typedefBuilder get() = KotlinTypedefBuilder(mainSlot, this)
    private val enumBuilder get() = KotlinEnumBuilder(mainSlot, this)
    private val objcProtocolBuilder get() = KotlinObjCProtocolBuilder(mainSlot, this)
    private val objcCategoryBuilder get() = KotlinObjCCategoryBuilder(mainSlot, this)
    // objcClassBuilder is recreated after the TOPLEVEL pre-scan populates generatedClassNames
    private var objcClassBuilder: KotlinObjCClassBuilder = KotlinObjCClassBuilder(mainSlot, this)

    /** Set after TOPLEVEL pre-scan for split-mode per-class builders. */
    private var _generatedClassNames: Set<String> = emptySet()

    /**
     * Maps each generated class name → superclass name (or null if root).
     * Built during the TOPLEVEL prescan.
     */
    private var _classHierarchy: ClassHierarchy = emptyMap()

    /**
     * Maps each generated class name → set of Kotlin method / property accessor signatures
     * declared directly on that class.  Built during the TOPLEVEL prescan and used for
     * override detection.
     */
    private var _classMethodSignatures: Map<String, Set<String>> = emptyMap()

    /** Counter for round-robin split across multiple function files (avoids <clinit> > 64KB). */
    private var _functionBatch: Int = 0
    private var _functionCount: Int = 0
    private val FUNCTIONS_PER_BATCH = 300

    /**
     * Mutable set of Kotlin signatures already emitted by category extension functions,
     * keyed by the extended class name.  Shared across all [KotlinObjCCategoryBuilder]
     * instances for the same class so that two categories with overlapping method names
     * do not produce "conflicting overloads".
     */
    private val _categorySignatures: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Set to true once we synthesise the NSObject root class. */
    private var _nsObjectGenerated: Boolean = false

    private fun getOrCreateSlot(key: String): SourceBuilder = slots.getOrPut(key) {
        val sb = SourceBuilder()
        // When splitting output into multiple compilation units, every file needs its own
        // package declaration and FFM imports.  The main slot gets these in the init block
        // below; sub-slots get them here.
        if (splitOutput && key != "_main") {
            if (targetPackage.isNotEmpty()) {
                sb.appendLine("package ${targetPackage}")
                sb.appendLine()
            }
            sb.appendLine("import java.lang.invoke.*")
            sb.appendLine("import java.lang.foreign.*")
            sb.appendLine("import java.lang.foreign.MemoryLayout.PathElement.*")
            sb.appendLine()
        }
        sb
    }
    private val mainSlot: SourceBuilder get() = getOrCreateSlot("_main")

    /** True if any ObjC declaration was encountered — triggers ObjCRuntime.kt emission. */
    var needsObjCRuntime: Boolean = false
        private set

    /** True when a LOOKUP val was generated (libraries were provided). */
    val hasLookup: Boolean get() = libraries.isNotEmpty()

    init {
        // Package declaration
        if (targetPackage.isNotEmpty()) {
            mainSlot.appendLine("package ${targetPackage}")
            mainSlot.appendLine()
        }

        // Standard imports
        mainSlot.appendLine("import java.lang.invoke.*")
        mainSlot.appendLine("import java.lang.foreign.*")
        mainSlot.appendLine("import java.lang.foreign.MemoryLayout.PathElement.*")
        mainSlot.appendLine()

        // Helper constants for layouts
        mainSlot.appendLine("private object kextract_runtime {")
        mainSlot.indent()
        mainSlot.appendLine("val C_BOOL: ValueLayout = ValueLayout.JAVA_BOOLEAN")
        mainSlot.appendLine("val C_CHAR: ValueLayout = ValueLayout.JAVA_BYTE")
        mainSlot.appendLine("val C_SHORT: ValueLayout = ValueLayout.JAVA_SHORT")
        mainSlot.appendLine("val C_INT: ValueLayout = ValueLayout.JAVA_INT")
        mainSlot.appendLine("val C_LONG: ValueLayout = ValueLayout.JAVA_LONG")
        mainSlot.appendLine("val C_LONG_LONG: ValueLayout = ValueLayout.JAVA_LONG")
        mainSlot.appendLine("val C_FLOAT: ValueLayout = ValueLayout.JAVA_FLOAT")
        mainSlot.appendLine("val C_DOUBLE: ValueLayout = ValueLayout.JAVA_DOUBLE")
        mainSlot.appendLine("val C_POINTER: ValueLayout = ValueLayout.ADDRESS")
        mainSlot.unindent()
        mainSlot.appendLine("}")
        mainSlot.appendLine()

        // Symbol lookup — loads native libraries and exposes a single LOOKUP
        if (libraries.isNotEmpty()) {
            mainSlot.appendLine("private val LOOKUP: SymbolLookup = run {")
            mainSlot.indent()
            if (useSystemLoadLibrary) {
                for (lib in libraries) {
                    mainSlot.appendLine("System.loadLibrary(\"${lib.libSpec}\")")
                }
                mainSlot.appendLine("SymbolLookup.loaderLookup()")
            } else {
                mainSlot.appendLine("var lu: SymbolLookup = SymbolLookup.loaderLookup()")
                for (lib in libraries) {
                    val lookup = when (lib.specKind) {
                        Options.Library.SpecKind.PATH ->
                            "SymbolLookup.libraryLookup(\"${Options.Library.toQuotedName(lib)}\", Arena.global())"
                        Options.Library.SpecKind.NAME ->
                            "SymbolLookup.libraryLookup(\"${lib.libSpec}\", Arena.global())"
                    }
                    mainSlot.appendLine("lu = $lookup.or(lu)")
                }
                mainSlot.appendLine("lu")
            }
            mainSlot.unindent()
            mainSlot.appendLine("}")
            mainSlot.appendLine()
        }
    }

    override fun visitScoped(decl: Declaration.Scoped) {
        if (Skip.isPresent(decl)) return
        when (decl.kind()) {
            Declaration.Scoped.Kind.STRUCT -> {
                if (splitOutput) {
                    KotlinStructBuilder(getOrCreateSlot("types"), this).visitStruct(decl)
                } else {
                    structBuilder.visitStruct(decl)
                }
            }
            Declaration.Scoped.Kind.UNION  -> {
                if (splitOutput) {
                    KotlinStructBuilder(getOrCreateSlot("types"), this).visitUnion(decl)
                } else {
                    structBuilder.visitUnion(decl)
                }
            }
            Declaration.Scoped.Kind.ENUM   -> {
                // Only generate for named enums with constants.
                // Anonymous enums (name == "") appear only as typedef targets and are never
                // emitted here since the typedef path handles them via typealias.
                // For ObjC fixed-underlying-type enums (typedef enum : long { … } Foo),
                // clang creates a named ENUM scoped with the typedef name, and the redundant
                // typedef is filtered — so this is the only place we emit the enum class.
                if (decl.name().isNotEmpty()) {
                    if (splitOutput) {
                        val slotKey = if (isOptionsStyle(decl.name())) "options" else "enums"
                        KotlinEnumBuilder(getOrCreateSlot(slotKey), this).visitEnum(decl)
                    } else {
                        enumBuilder.visitEnum(decl)
                    }
                }
            }
            else -> {
                // TOPLEVEL: pre-scan before generating code.
                if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
                    // Collect the names of all enum constants from named ENUMs inside TOPLEVEL.
                    // Mark those named-ENUM scopeds and their constants as Skip so they are not
                    // re-visited as standalone items.
                    // Also mark any top-level macro Declaration.Constant whose name matches an
                    // enum constant (clang synthesises these for each ObjC enum member).
                    val enumConstantNames = mutableSetOf<String>()
                    decl.members()
                        .filterIsInstance<Declaration.Scoped>()
                        .filter { it.kind() == Declaration.Scoped.Kind.ENUM && it.name().isNotEmpty() && !Skip.isPresent(it) }
                        .forEach { enumScoped ->
                            enumScoped.members()
                                .filterIsInstance<Declaration.Constant>()
                                .forEach { constant ->
                                    enumConstantNames.add(constant.name())
                                    // The constants themselves will be emitted inside the enum
                                    // class; mark them so they are not re-emitted as globals.
                                    Skip.with(constant)
                                }
                        }
                    // Suppress macro-synthesised constants that shadow enum member names
                    if (enumConstantNames.isNotEmpty()) {
                        decl.members()
                            .filterIsInstance<Declaration.Constant>()
                            .filter { it.name() in enumConstantNames && !Skip.isPresent(it) }
                            .forEach { constant -> Skip.with(constant) }
                    }
                    // Collect generated ObjCClass names so the class builder can emit superclass
                    // clauses only for classes that will actually be generated (GRA-79).
                    val generatedObjCClassNames = decl.members()
                        .filterIsInstance<Declaration.ObjCClass>()
                        .filter { !Skip.isPresent(it) }
                        .map { it.name() }
                        .toSet()
                    val modifiedClassNames = generatedObjCClassNames.toMutableSet()
                    // If a generated class references NSObject as superclass but NSObject is
                    // itself not being generated (it lives in the SDK but outside the
                    // --include-framework filter), synthesize it so that:
                    //   1. Subclasses can emit ": NSObject(ptr)" and "override val ptr"
                    //   2. Categories on NSObject (extension functions) can resolve "this.ptr"
                    val needsNSObject = generatedObjCClassNames.any { clsName ->
                        decl.members()
                            .filterIsInstance<Declaration.ObjCClass>()
                            .firstOrNull { it.name() == clsName && !Skip.isPresent(it) }
                            ?.superClass() == "NSObject"
                    }
                    if (needsNSObject && "NSObject" !in generatedObjCClassNames) {
                        modifiedClassNames.add("NSObject")
                    }
                    _generatedClassNames = modifiedClassNames

                    // Build class hierarchy and method-signature maps for override detection.
                    val hierarchy = mutableMapOf<String, String?>()
                    val methodSigs = mutableMapOf<String, Set<String>>()
                    for (cls in decl.members().filterIsInstance<Declaration.ObjCClass>().filter { !Skip.isPresent(it) }) {
                        hierarchy[cls.name()] = cls.superClass()
                        methodSigs[cls.name()] = extractClassSignatures(cls)
                    }
                    _classHierarchy = hierarchy
                    _classMethodSignatures = methodSigs
                    objcClassBuilder = KotlinObjCClassBuilder(
                        mainSlot, this, generatedObjCClassNames,
                        hierarchy, methodSigs
                    )
                }
                // Process all members
                for (d in decl.members()) {
                    d.accept(this)
                }
                // After all ObjC classes have been visited, synthesise NSObject if it was
                // added to _generatedClassNames during the prescan but never visited.
                if ("NSObject" in _generatedClassNames && !_nsObjectGenerated) {
                    generateNSObjectClass()
                }
            }
        }

        // Only add file for TOPLEVEL scoped (not for nested structs/unions)
        if (decl.kind() == Declaration.Scoped.Kind.TOPLEVEL) {
            if (!splitOutput) {
                files.add(KotlinSourceFile(targetPackage, className, mainSlot.toString()))
            }
        }
    }

    override fun visitFunction(decl: Declaration.Function) {
        if (Skip.isPresent(decl)) return
        if (splitOutput) {
            val slotKey = if (_functionBatch == 0) "functions" else "functions$_functionBatch"
            KotlinHeaderBuilder(getOrCreateSlot(slotKey), this).visitFunction(decl)
            _functionCount++
            if (_functionCount >= FUNCTIONS_PER_BATCH) {
                _functionCount = 0
                _functionBatch++
            }
        } else {
            headerBuilder.visitFunction(decl)
        }
    }

    override fun visitVariable(decl: Declaration.Variable) {
        if (Skip.isPresent(decl)) return
        if (splitOutput) {
            val slotKey = if (_functionBatch == 0) "functions" else "functions$_functionBatch"
            KotlinHeaderBuilder(getOrCreateSlot(slotKey), this).visitVariable(decl)
            _functionCount++
            if (_functionCount >= FUNCTIONS_PER_BATCH) {
                _functionCount = 0
                _functionBatch++
            }
        } else {
            headerBuilder.visitVariable(decl)
        }
    }

    override fun visitTypedef(decl: Declaration.Typedef) {
        if (Skip.isPresent(decl)) return
        val sb = if (splitOutput) getOrCreateSlot("types") else mainSlot
        KotlinTypedefBuilder(sb, this).visitTypedef(decl)
    }

    override fun visitConstant(decl: Declaration.Constant) {
        if (Skip.isPresent(decl)) return
        if (splitOutput) {
            KotlinHeaderBuilder(getOrCreateSlot("enums"), this).visitConstant(decl)
        } else {
            headerBuilder.visitConstant(decl)
        }
    }

    override fun visitObjCClass(decl: Declaration.ObjCClass) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        if (splitOutput) {
            val sb = getOrCreateSlot("class.${decl.name()}")
            KotlinObjCClassBuilder(sb, this, _generatedClassNames, _classHierarchy, _classMethodSignatures).visitClass(decl)
        } else {
            objcClassBuilder.visitClass(decl)
        }
    }

    private fun generateNSObjectClass() {
        if (!splitOutput) return
        _nsObjectGenerated = true
        val sb = getOrCreateSlot("class.NSObject")
        sb.appendLine("/**")
        sb.appendLine(" * Kotlin/JVM wrapper for root class NSObject.")
        sb.appendLine(" * Synthesised because it is referenced as a superclass by generated classes")
        sb.appendLine(" * but was not included in the framework filter set.")
        sb.appendLine(" */")
        sb.appendLine("open class NSObject(open val ptr: MemorySegment) {")
        sb.indent()
        sb.appendLine("companion object {")
        sb.indent()
        sb.appendLine("private val _class: MemorySegment by lazy { ObjCRuntime.getClass(\"NSObject\") }")
        sb.unindent()
        sb.appendLine("}")
        sb.appendLine()
        sb.unindent()
        sb.appendLine("}")
        sb.appendLine()
    }

    override fun visitObjCProtocol(decl: Declaration.ObjCProtocol) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        // Skip protocols whose name collides with a generated class (e.g. NSAccessibilityElement
        // exists as both @interface and @protocol) — the class takes precedence.
        if (decl.name() in _generatedClassNames) return
        if (splitOutput) {
            val sb = getOrCreateSlot("protocol.${decl.name()}")
            KotlinObjCProtocolBuilder(sb, this, _generatedClassNames).visitProtocol(decl)
        } else {
            objcProtocolBuilder.visitProtocol(decl)
        }
    }

    override fun visitObjCCategory(decl: Declaration.ObjCCategory) {
        if (Skip.isPresent(decl)) return
        needsObjCRuntime = true
        if (splitOutput) {
            val sb = getOrCreateSlot("class.${decl.extendedClass()}")
            val classSigs = _classMethodSignatures[decl.extendedClass()] ?: emptySet()
            val shared = _categorySignatures.getOrPut(decl.extendedClass()) {
                classSigs.toMutableSet()
            }
            KotlinObjCCategoryBuilder(sb, this, shared).visitCategory(decl)
        } else {
            objcCategoryBuilder.visitCategory(decl)
        }
    }

    fun getFiles(): List<KotlinSourceFile> {
        if (splitOutput) {
            return slots.map { (key, sb) ->
                val (subdir, name) = slotToFile(key)
                KotlinSourceFile(targetPackage, name, sb.toString(), subdir)
            }
        }
        return files
    }

    private fun slotToFile(key: String): Pair<String, String> = when {
        key == "_main" -> "" to className
        key == "types" -> "types" to "${headerBaseName}Types"
        key == "enums" -> "enums" to "${headerBaseName}Enums"
        key == "options" -> "options" to "${headerBaseName}Options"
        key == "functions" -> "functions" to "${headerBaseName}Functions"
        key.startsWith("functions") -> "functions" to "${headerBaseName}Functions_${key.removePrefix("functions")}"
        key.startsWith("class.") -> "classes" to key.removePrefix("class.")
        key.startsWith("protocol.") -> "protocols" to key.removePrefix("protocol.")
        else -> "" to key.replace('.', '_')
    }

    private fun isOptionsStyle(name: String): Boolean =
        name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask")

    fun javaName(name: String): String = KotlinNameMangler.mangle(name)

    fun lookupName(decl: Declaration): String = decl.name()

    /**
     * Extracts the set of Kotlin method and property-accessor signatures from an ObjC class
     * declaration.  These are used downstream to detect method overrides.
     */
    private fun extractClassSignatures(decl: Declaration.ObjCClass): Set<String> {
        val sigs = mutableSetOf<String>()
        for (method in decl.methods()) {
            sigs.add(KotlinObjCClassBuilder.kotlinName(method.selector()))
        }
        for (prop in decl.properties()) {
            sigs.add(KotlinObjCClassBuilder.kotlinName(prop.getterSelector()))
            if (!prop.isReadOnly()) {
                sigs.add(KotlinObjCClassBuilder.kotlinName(prop.setterSelector().removeSuffix(":")))
            }
        }
        return sigs
    }
}
