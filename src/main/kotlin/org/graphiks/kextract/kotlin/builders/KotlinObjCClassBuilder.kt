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

        // Instance methods (-) — deduplicate by Kotlin name to avoid colliding function signatures
        val seenInstanceMethods = LinkedHashSet<String>()
        val uniqueInstanceMethods = decl.methods()
            .filter { !it.isClassMethod() }
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

        val argsList = params.mapIndexed { i, p -> p.name().ifEmpty { "arg$i" } }.joinToString(", ")
        val argsExpr = if (argsList.isEmpty()) "" else ", $argsList"

        val isStructReturn = retType is Type.Declared
        if (retKotlin == "Unit") {
            builder.appendLine("ObjCRuntime.msgSend(null, $receiver, sel$argsExpr)")
        } else if (isStructReturn) {
            // Note: uses msgSendStret on x86-64 for large struct returns
            builder.appendLine("return ObjCRuntime.msgSendStret($retLayout, $receiver, sel$argsExpr) as $retKotlin")
        } else {
            builder.appendLine("return ObjCRuntime.msgSend($retLayout, $receiver, sel$argsExpr) as $retKotlin")
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
     * - NSString **return**: emits `fun methodNameAsString(): String = ObjCRuntime.toJavaString(methodName())`
     * - NSString **parameter(s)**: emits an overload where each NSString parameter accepts `String`
     *   and is wrapped with `ObjCRuntime.newNSString(Arena.global(), value)` before forwarding.
     *
     * Both kinds of overload are skipped when neither case applies.
     */
    private fun emitNSStringMethodOverloads(method: Declaration.ObjCMethod, receiver: String) {
        val selector = method.selector()
        val params = method.parameters()
        val retType = method.returnType()
        val fnName = kotlinName(selector)
        val nsStringReturnType = isNSString(retType)
        val nsStringParams = params.map { isNSString(it.type()) }
        val hasNSStringParam = nsStringParams.any { it }

        // Return-type overload: fun methodNameAsString(): String
        if (nsStringReturnType) {
            builder.appendLine("/** Convenience overload — returns Kotlin [String] by converting the NSString via UTF8String. */")
            builder.appendLine("fun ${fnName}AsString(): String = ObjCRuntime.toJavaString($fnName())")
            builder.appendLine()
        }

        // Parameter overload: replace NSString params with String
        if (hasNSStringParam) {
            val stringParamList = params.mapIndexed { i, p ->
                val pName = p.name().ifEmpty { "arg$i" }
                val pType = if (nsStringParams[i]) "String" else TypeMapper.map(p.type())
                "$pName: $pType"
            }.joinToString(", ")

            val forwardArgs = params.mapIndexed { i, p ->
                val pName = p.name().ifEmpty { "arg$i" }
                if (nsStringParams[i]) "ObjCRuntime.newNSString(Arena.global(), $pName)" else pName
            }.joinToString(", ")

            val retKotlin = returnTypeKotlin(retType)
            val retDecl = if (retKotlin == "Unit") ": Unit" else ": $retKotlin"

            builder.appendLine("/** Convenience overload — accepts Kotlin [String] for NSString parameters. */")
            builder.appendLine("fun $fnName($stringParamList)$retDecl = $fnName($forwardArgs)")
            builder.appendLine()
        }
    }

    private fun emitProperty(prop: Declaration.ObjCProperty) {
        val propName = prop.name()
        val retKotlin = returnTypeKotlin(prop.type())
        val retLayout = returnLayout(prop.type())
        val getter = prop.getterSelector()
        val propTypeSpelling = prop.typeSpelling()

        val isStructReturn = prop.type() is Type.Declared
        builder.appendLine("// @property $propName")
        // Emit a KDoc comment when the original ObjC property type carries generic information
        // (e.g. "NSArray<NSString *> *") that is erased to MemorySegment in the Kotlin binding.
        if (propTypeSpelling.contains('<')) {
            builder.appendLine("/** @return $propTypeSpelling */")
        }
        builder.appendLine("fun ${kotlinName(getter)}(): $retKotlin {")
        builder.indent()
        builder.appendLine("val sel = ObjCRuntime.sel(\"$getter\")")
        if (retKotlin == "Unit") {
            builder.appendLine("ObjCRuntime.msgSend(null, ptr, sel)")
        } else if (isStructReturn) {
            // Note: uses msgSendStret on x86-64 for large struct returns
            builder.appendLine("return ObjCRuntime.msgSendStret($retLayout, ptr, sel) as $retKotlin")
        } else {
            builder.appendLine("return ObjCRuntime.msgSend($retLayout, ptr, sel) as $retKotlin")
        }
        builder.unindent()
        builder.appendLine("}")

        if (!prop.isReadOnly()) {
            val setter = prop.setterSelector()
            val paramType = TypeMapper.map(prop.type())
            builder.appendLine("fun ${kotlinName(setter.removeSuffix(":"))}(value: $paramType) {")
            builder.indent()
            builder.appendLine("val sel = ObjCRuntime.sel(\"$setter\")")
            builder.appendLine("ObjCRuntime.msgSend(null, ptr, sel, value)")
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

    /** Returns true when [type] is the NSString typedef (a TYPEDEF-kind Delegated whose name is "NSString"). */
    private fun isNSString(type: Type): Boolean =
        type is Type.Delegated &&
        type.kind() == Type.Delegated.Kind.TYPEDEF &&
        type.name() == "NSString"

    companion object {
        /** Maps an ObjC return type to the Kotlin type name. */
        fun returnTypeKotlin(type: Type): String = when {
            type is Type.Primitive && type.kind() == Type.Primitive.Kind.Void -> "Unit"
            else -> TypeMapper.map(type)
        }

        /** Returns the Panama MemoryLayout expression for the return type (null for void). */
        fun returnLayout(type: Type): String = when {
            type is Type.Primitive && type.kind() == Type.Primitive.Kind.Void -> "null"
            type is Type.Primitive -> when (type.kind()) {
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
            else -> "ValueLayout.ADDRESS"  // pointers, ObjC objects, structs, etc.
        }

        /**
         * Converts an ObjC selector to a valid Kotlin function name.
         * "stringWithUTF8String:" → "stringWithUTF8String"
         * "setLength:" → "setLength"
         */
        fun kotlinName(selector: String): String =
            selector.replace(":", "_").trimEnd('_')
    }
}
