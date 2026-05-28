package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.Type
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.kotlin.utils.TypeMapper

/**
 * Generates a Kotlin class wrapper for an Objective-C @interface declaration.
 *
 * Example output for `@interface NSString : NSObject`:
 * ```kotlin
 * open class NSString(val ptr: MemorySegment) {
 *     companion object {
 *         private val _class: MemorySegment by lazy { ObjCRuntime.getClass("NSString") }
 *         fun stringWithUTF8String(cString: MemorySegment): MemorySegment { ... }
 *     }
 *     fun length(): Long { ... }
 * }
 * ```
 *
 * When a superclass is also being generated in the same run its name will appear in
 * [generatedClassNames] and the Kotlin class will extend it, passing `ptr` to `super`.
 */
class KotlinObjCClassBuilder(
    private val builder: SourceBuilder,
    private val toplevel: KotlinToplevelBuilder,
    private val generatedClassNames: Set<String> = emptySet()
) {

    fun visitClass(decl: Declaration.ObjCClass) {
        if (Skip.isPresent(decl)) return

        val className = decl.name()
        val superClass = decl.superClass()

        // KDoc header
        builder.appendLine("/**")
        builder.appendLine(" * Kotlin/JVM wrapper for Objective-C class: $className")
        if (superClass != null) builder.appendLine(" * Superclass: $superClass")
        if (decl.protocols().isNotEmpty())
            builder.appendLine(" * Protocols: ${decl.protocols().joinToString()}")
        builder.appendLine(" */")

        // Emit the superclass clause only when the superclass is also being generated in this
        // run (i.e. it is not Skip-marked and therefore present in generatedClassNames).
        // System-framework root classes such as NSObject are typically not generated, so we
        // fall back to a standalone wrapper in that case.
        // Root classes declare `val ptr` as a property; derived classes just pass it through
        // so they don't re-declare a property that is already inherited.
        val superExpr = if (superClass != null && superClass in generatedClassNames)
            " : $superClass(ptr)" else ""
        val ptrParam = if (superExpr.isNotEmpty()) "ptr: MemorySegment" else "val ptr: MemorySegment"
        builder.appendLine("open class $className($ptrParam)$superExpr {")
        builder.indent()

        // Companion object for class-level methods and the Class reference
        builder.appendLine("companion object {")
        builder.indent()
        // If a library was specified, reference LOOKUP to force it to load
        // before we ask the ObjC runtime for the class (which requires the dylib to be loaded).
        if (toplevel.hasLookup) {
            builder.appendLine("private val _class: MemorySegment by lazy { LOOKUP.let { ObjCRuntime.getClass(\"$className\") } }")
        } else {
            builder.appendLine("private val _class: MemorySegment by lazy { ObjCRuntime.getClass(\"$className\") }")
        }
        builder.appendLine()

        // Class methods (+) — deduplicate by Kotlin name to avoid colliding function signatures
        val seenClassMethods = LinkedHashSet<String>()
        val uniqueClassMethods = decl.methods()
            .filter { it.isClassMethod() }
            .filter { seenClassMethods.add(kotlinName(it.selector())) }
        for (method in uniqueClassMethods) {
            emitMethod(method, receiver = "_class")
        }

        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        // Collect all selectors already covered by property getter/setter synthesis so that
        // we don't emit a plain method AND a property accessor with the same signature.
        // ObjC synthesises a getter (and optional setter) method for every @property, so
        // the same selector appears in both decl.methods() and decl.properties().
        val propertySelectors: Set<String> = decl.properties()
            .flatMapTo(mutableSetOf()) { prop ->
                buildList {
                    add(prop.getterSelector())
                    if (!prop.isReadOnly()) add(prop.setterSelector())
                }
            }

        // Instance methods (-) — deduplicate by Kotlin name; skip any selector already emitted
        // as a property accessor to avoid "conflicting overloads" in the generated source.
        val seenInstanceMethods = LinkedHashSet<String>()
        val uniqueInstanceMethods = decl.methods()
            .filter { !it.isClassMethod() }
            .filter { it.selector() !in propertySelectors }
            .filter { seenInstanceMethods.add(kotlinName(it.selector())) }
        for (method in uniqueInstanceMethods) {
            emitMethod(method, receiver = "ptr")
        }

        // Properties — deduplicate by property name to avoid redeclaring the same getter/setter
        val seenProperties = LinkedHashSet<String>()
        val uniqueProperties = decl.properties()
            .filter { seenProperties.add(it.name()) }
        for (prop in uniqueProperties) {
            emitProperty(prop)
        }

        // Instance variables — emitted as comments since direct field access is not
        // supported via the Panama FFI (ObjC ivars are not part of the stable ABI).
        val ivars = decl.ivars()
        if (ivars.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("// ── Instance variables (direct field access not supported via Panama) ──")
            for (ivar in ivars) {
                builder.appendLine("// ivar: ${ivar.name()}: ${TypeMapper.map(ivar.type())}")
            }
        }

        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()
    }

    internal fun emitMethod(method: Declaration.ObjCMethod, receiver: String) {
        val selector = method.selector()
        val params = method.parameters()
        val retType = method.returnType()
        val retKotlin = returnTypeKotlin(retType)
        val retLayout = returnLayout(retType)
        val retSpelling = method.returnTypeSpelling()

        val paramList = params.mapIndexed { i, p ->
            val pName = p.name().ifEmpty { "arg$i" }
            val pType = TypeMapper.map(p.type())
            "$pName: $pType"
        }.joinToString(", ")

        val retDecl = if (retKotlin == "Unit") ": Unit" else ": $retKotlin"

        // Emit a KDoc comment when the original ObjC return type carries generic information
        // (e.g. "NSArray<NSString *> *") that is erased to MemorySegment in the Kotlin binding.
        if (retSpelling.contains('<')) {
            builder.appendLine("/** @return $retSpelling */")
        }
        builder.appendLine("fun ${kotlinName(selector)}($paramList)$retDecl {")
        builder.indent()
        builder.appendLine("val sel = ObjCRuntime.sel(\"$selector\")")

        // Unbox enum/value-class arguments so ObjCRuntime.layoutFor() sees a primitive.
        // NS_ENUM → `.value`; NS_OPTIONS (ends with Options/Flags/Mask) → `.rawValue`.
        val argsList = params.mapIndexed { i, p ->
            val pName = p.name().ifEmpty { "arg$i" }
            unboxedArgExpr(p.type(), pName)
        }.joinToString(", ")
        val argsExpr = if (argsList.isEmpty()) "" else ", $argsList"

        when {
            retKotlin == "Unit" -> {
                builder.appendLine("ObjCRuntime.msgSend(null, $receiver, sel$argsExpr)")
            }
            isStructByValue(retType) -> {
                // Struct-by-value return: use msgSendStret with a GroupLayout.
                // On ARM64 the struct is returned in registers; on x86-64 msgSend_stret is used.
                builder.appendLine("return ObjCRuntime.msgSendStret($retLayout, $receiver, sel$argsExpr) as $retKotlin")
            }
            else -> {
                val enumDecl = resolveDecl(retType, Declaration.Scoped.Kind.ENUM)
                if (enumDecl != null) {
                    // Enum/value-class return: get the underlying Long and re-box it.
                    val raw = "ObjCRuntime.msgSend(ValueLayout.JAVA_LONG, $receiver, sel$argsExpr) as Long"
                    if (isOptionsStyle(enumDecl.name()))
                        builder.appendLine("return $retKotlin($raw)")
                    else
                        builder.appendLine("return $retKotlin.fromValue($raw)")
                } else {
                    builder.appendLine("return ObjCRuntime.msgSend($retLayout, $receiver, sel$argsExpr) as $retKotlin")
                }
            }
        }
        builder.unindent()
        builder.appendLine("}")
        builder.appendLine()

        // Emit String convenience overloads for NSString parameters / return type
        emitNSStringMethodOverloads(method, receiver)
    }

    /**
     * Emits convenience overloads when a method has NSString parameters or returns NSString.
     *
     * Up to three overloads may be generated (in addition to the raw base method):
     *
     * 1. **Return-type overload** (`AsString` suffix) — forwards all params as-is (MemorySegment)
     *    and wraps the NSString return value:
     *    `fun fooAsString(p: MemorySegment): String = ObjCRuntime.toJavaString(foo(p))`
     *
     * 2. **Parameter overload** — replaces each NSString param with `String` and wraps it:
     *    `fun foo(p: String): MemorySegment = foo(ObjCRuntime.newNSString(Arena.global(), p))`
     *
     * 3. **Combined overload** (only when both conditions hold) — String params + String return:
     *    `fun fooAsString(p: String): String = ObjCRuntime.toJavaString(foo(ObjCRuntime.newNSString(...)))`
     *
     * All overloads are skipped when neither condition applies.
     */
    private fun emitNSStringMethodOverloads(method: Declaration.ObjCMethod, receiver: String) {
        val params = method.parameters()
        val retType = method.returnType()
        val fnName = kotlinName(method.selector())
        val nsStringReturnType = isNSString(retType)
        val nsStringParams = params.map { isNSString(it.type()) }
        val hasNSStringParam = nsStringParams.any { it }

        if (!nsStringReturnType && !hasNSStringParam) return

        // Raw param list — MemorySegment for NSString params (same as base method)
        val rawParamList = params.mapIndexed { i, p ->
            val pName = p.name().ifEmpty { "arg$i" }
            "$pName: ${TypeMapper.map(p.type())}"
        }.joinToString(", ")
        val rawArgs = params.mapIndexed { i, p -> p.name().ifEmpty { "arg$i" } }.joinToString(", ")

        // String param list — String for NSString params, MemorySegment for the rest
        val stringParamList = params.mapIndexed { i, p ->
            val pName = p.name().ifEmpty { "arg$i" }
            val pType = if (nsStringParams[i]) "String" else TypeMapper.map(p.type())
            "$pName: $pType"
        }.joinToString(", ")
        val wrappedArgs = params.mapIndexed { i, p ->
            val pName = p.name().ifEmpty { "arg$i" }
            if (nsStringParams[i]) "ObjCRuntime.newNSString(Arena.global(), $pName)" else pName
        }.joinToString(", ")

        val retKotlin = returnTypeKotlin(retType)
        val retDecl = if (retKotlin == "Unit") ": Unit" else ": $retKotlin"

        // Overload 1: NSString return → AsString suffix, raw (MemorySegment) params
        if (nsStringReturnType) {
            builder.appendLine("/** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */")
            builder.appendLine("fun ${fnName}AsString($rawParamList): String = ObjCRuntime.toJavaString($fnName($rawArgs))")
            builder.appendLine()
        }

        // Overload 2: NSString param(s) → String params, original return type
        if (hasNSStringParam) {
            builder.appendLine("/** Convenience overload — accepts Kotlin [String] for NSString parameters. */")
            builder.appendLine("fun $fnName($stringParamList)$retDecl = $fnName($wrappedArgs)")
            builder.appendLine()

            // Overload 3 (combined): String params + String return — only when return is also NSString
            if (nsStringReturnType) {
                builder.appendLine("/** Convenience overload — [String] parameters and [String] return type. */")
                builder.appendLine("fun ${fnName}AsString($stringParamList): String = ObjCRuntime.toJavaString($fnName($wrappedArgs))")
                builder.appendLine()
            }
        }
    }

    private fun emitProperty(prop: Declaration.ObjCProperty) {
        val propName = prop.name()
        val retKotlin = returnTypeKotlin(prop.type())
        val retLayout = returnLayout(prop.type())
        val getter = prop.getterSelector()
        val propTypeSpelling = prop.typeSpelling()

        builder.appendLine("// @property $propName")
        // Emit a KDoc comment when the original ObjC property type carries generic information
        // (e.g. "NSArray<NSString *> *") that is erased to MemorySegment in the Kotlin binding.
        if (propTypeSpelling.contains('<')) {
            builder.appendLine("/** @return $propTypeSpelling */")
        }
        builder.appendLine("fun ${kotlinName(getter)}(): $retKotlin {")
        builder.indent()
        builder.appendLine("val sel = ObjCRuntime.sel(\"$getter\")")
        when {
            retKotlin == "Unit" -> builder.appendLine("ObjCRuntime.msgSend(null, ptr, sel)")
            isStructByValue(prop.type()) -> {
                builder.appendLine("return ObjCRuntime.msgSendStret($retLayout, ptr, sel) as $retKotlin")
            }
            else -> {
                val enumDecl = resolveDecl(prop.type(), Declaration.Scoped.Kind.ENUM)
                if (enumDecl != null) {
                    val raw = "ObjCRuntime.msgSend(ValueLayout.JAVA_LONG, ptr, sel) as Long"
                    if (isOptionsStyle(enumDecl.name()))
                        builder.appendLine("return $retKotlin($raw)")
                    else
                        builder.appendLine("return $retKotlin.fromValue($raw)")
                } else {
                    builder.appendLine("return ObjCRuntime.msgSend($retLayout, ptr, sel) as $retKotlin")
                }
            }
        }
        builder.unindent()
        builder.appendLine("}")

        if (!prop.isReadOnly()) {
            val setter = prop.setterSelector()
            val paramType = TypeMapper.map(prop.type())
            val valueExpr = unboxedArgExpr(prop.type(), "value")
            builder.appendLine("fun ${kotlinName(setter.removeSuffix(":"))}(value: $paramType) {")
            builder.indent()
            builder.appendLine("val sel = ObjCRuntime.sel(\"$setter\")")
            builder.appendLine("ObjCRuntime.msgSend(null, ptr, sel, $valueExpr)")
            builder.unindent()
            builder.appendLine("}")
        }
        builder.appendLine()

        // NSString convenience overloads for properties
        if (isNSString(prop.type())) {
            val getterFn = kotlinName(getter)
            // Getter: String overload
            builder.appendLine("/** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */")
            builder.appendLine("fun ${getterFn}AsString(): String = ObjCRuntime.toJavaString($getterFn())")
            builder.appendLine()
            // Setter: String overload (only for readwrite properties)
            if (!prop.isReadOnly()) {
                val setterFn = kotlinName(prop.setterSelector().removeSuffix(":"))
                builder.appendLine("/** Convenience overload — accepts Kotlin [String] for the NSString property. */")
                builder.appendLine("fun $setterFn(value: String) = $setterFn(ObjCRuntime.newNSString(Arena.global(), value))")
                builder.appendLine()
            }
        }
    }

    /**
     * Returns true when [type] represents NSString or NSString*.
     *
     * In practice Foundation headers always use pointer types (`NSString *`), so the
     * libclang type tree is:
     *   Type.Delegated(POINTER) → Type.Delegated(TYPEDEF, name="NSString")
     *
     * We also handle the bare-typedef case just in case.
     */
    private fun isNSString(type: Type): Boolean {
        if (type is Type.Delegated) {
            // Direct typedef: NSString (rare)
            if (type.kind() == Type.Delegated.Kind.TYPEDEF && type.name() == "NSString") return true
            // Pointer to typedef: NSString * (the common case from Foundation headers)
            if (type.kind() == Type.Delegated.Kind.POINTER) {
                val inner = type.type()
                if (inner is Type.Delegated &&
                    inner.kind() == Type.Delegated.Kind.TYPEDEF &&
                    inner.name() == "NSString") return true
            }
        }
        return false
    }

    companion object {
        /** Maps an ObjC return type to the Kotlin type name. */
        fun returnTypeKotlin(type: Type): String = when {
            type is Type.Primitive && type.kind() == Type.Primitive.Kind.Void -> "Unit"
            else -> TypeMapper.map(type)
        }

        /** Returns the Panama MemoryLayout expression for the return type (null for void). */
        fun returnLayout(type: Type): String = when {
            type is Type.Primitive && type.kind() == Type.Primitive.Kind.Void -> "null"
            type is Type.Primitive -> primitiveLayout(type.kind())
            // Pointers stay as ADDRESS; all other delegated types (typedef, signed/unsigned
            // qualifiers) are unwrapped so that e.g. `CGFloat` → JAVA_DOUBLE, `BOOL` → JAVA_BYTE.
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> "ValueLayout.ADDRESS"
            type is Type.Delegated -> returnLayout(type.type())
            // Struct by value → GroupLayout (used with msgSendStret).
            type is Type.Declared && type.tree().kind() == Declaration.Scoped.Kind.STRUCT ->
                structGroupLayoutExpr(type.tree())
            // NS_ENUM / NS_OPTIONS → underlying Long (emitMethod handles the re-boxing).
            type is Type.Declared && type.tree().kind() == Declaration.Scoped.Kind.ENUM ->
                "ValueLayout.JAVA_LONG"
            else -> "ValueLayout.ADDRESS"  // ObjC objects, opaque types
        }

        // ── Layout helpers ────────────────────────────────────────────────────

        private fun primitiveLayout(kind: Type.Primitive.Kind): String = when (kind) {
            Type.Primitive.Kind.Bool     -> "ValueLayout.JAVA_BOOLEAN"
            Type.Primitive.Kind.Char     -> "ValueLayout.JAVA_BYTE"
            Type.Primitive.Kind.Short    -> "ValueLayout.JAVA_SHORT"
            Type.Primitive.Kind.Int      -> "ValueLayout.JAVA_INT"
            Type.Primitive.Kind.Long,
            Type.Primitive.Kind.LongLong -> "ValueLayout.JAVA_LONG"
            Type.Primitive.Kind.Float    -> "ValueLayout.JAVA_FLOAT"
            Type.Primitive.Kind.Double   -> "ValueLayout.JAVA_DOUBLE"
            else                         -> "ValueLayout.ADDRESS"
        }

        /**
         * Builds an inline `MemoryLayout.structLayout(…)` expression for [decl].
         *
         * Used as the return-layout argument for [ObjCRuntime.msgSendStret].  Nested struct
         * fields are expanded recursively.  The struct's own name is appended as `.withName`.
         */
        fun structGroupLayoutExpr(decl: Declaration.Scoped): String {
            val fields = decl.members()
                .filterIsInstance<Declaration.Variable>()
                .filter { it.kind() == Declaration.Variable.Kind.FIELD }
            val fieldExprs = fields.joinToString(", ") { f ->
                "${structFieldLayoutExpr(f.type())}.withName(\"${f.name()}\")"
            }
            val name = decl.name()
            val suffix = if (name.isNotEmpty()) ".withName(\"$name\")" else ""
            return "MemoryLayout.structLayout($fieldExprs)$suffix"
        }

        /** Layout expression for a single struct field (no outer `.withName` — caller adds it). */
        private fun structFieldLayoutExpr(type: Type): String = when {
            type is Type.Primitive -> primitiveLayout(type.kind())
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER -> "ValueLayout.ADDRESS"
            type is Type.Delegated -> structFieldLayoutExpr(type.type())
            type is Type.Declared && type.tree().kind() == Declaration.Scoped.Kind.STRUCT -> {
                // Inline nested struct without adding its own .withName (caller adds field name).
                val fields = type.tree().members()
                    .filterIsInstance<Declaration.Variable>()
                    .filter { it.kind() == Declaration.Variable.Kind.FIELD }
                val fieldExprs = fields.joinToString(", ") { f ->
                    "${structFieldLayoutExpr(f.type())}.withName(\"${f.name()}\")"
                }
                "MemoryLayout.structLayout($fieldExprs)"
            }
            type is Type.Declared && type.tree().kind() == Declaration.Scoped.Kind.ENUM ->
                "ValueLayout.JAVA_INT"   // C enums default to int
            else -> "ValueLayout.ADDRESS"
        }

        /**
         * Converts an ObjC selector to a valid Kotlin function name.
         * "stringWithUTF8String:" → "stringWithUTF8String"
         * "setLength:" → "setLength"
         */
        fun kotlinName(selector: String): String =
            selector.replace(":", "_").trimEnd('_')

        /**
         * Returns the expression to pass for [name] when dispatching via ObjCRuntime.msgSend.
         *
         * - **NS_OPTIONS** (`@JvmInline value class`, rawValue: Long) → `name.rawValue`
         * - **NS_ENUM** (enum class, value: Long) → `name.value`
         * - **Struct by value** → `ObjCRuntime.ObjCStructArg(name, layoutExpr)` so that
         *   ObjCRuntime can use the correct GroupLayout instead of ValueLayout.ADDRESS.
         * - Everything else → `name` unchanged.
         */
        fun unboxedArgExpr(type: Type, name: String): String {
            val enumDecl = resolveDecl(type, Declaration.Scoped.Kind.ENUM)
            if (enumDecl != null) {
                return if (isOptionsStyle(enumDecl.name())) "$name.rawValue" else "$name.value"
            }
            val structDecl = resolveDecl(type, Declaration.Scoped.Kind.STRUCT)
            if (structDecl != null) {
                return "ObjCRuntime.ObjCStructArg($name, ${structGroupLayoutExpr(structDecl)})"
            }
            return name
        }

        /**
         * Returns true when [type] resolves (through typedefs/qualifiers, not pointers) to a
         * struct declaration — indicating the value is passed / returned by value, not by pointer.
         */
        fun isStructByValue(type: Type): Boolean =
            resolveDecl(type, Declaration.Scoped.Kind.STRUCT) != null

        /**
         * Traverses typedef / qualifier wrappers (but NOT pointer indirections) and returns the
         * innermost [Declaration.Scoped] whose kind matches [target], or null if not found.
         */
        fun resolveDecl(type: Type, target: Declaration.Scoped.Kind): Declaration.Scoped? = when {
            type is Type.Declared && type.tree().kind() == target -> type.tree()
            type is Type.Delegated && type.kind() != Type.Delegated.Kind.POINTER ->
                resolveDecl(type.type(), target)
            else -> null
        }

        private fun isOptionsStyle(name: String): Boolean =
            name.endsWith("Options") || name.endsWith("Flags") || name.endsWith("Mask")
    }
}
