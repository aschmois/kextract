package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.ClangEnumType
import org.graphiks.kextract.DeclarationImpl.JavaName
import org.graphiks.kextract.Type
import org.graphiks.kextract.TypeImpl
import org.graphiks.kextract.pipeline.isEnum
import org.graphiks.kextract.pipeline.isStructOrUnion

/**
 * The raw C ABI shape of a callback parameter.
 *
 * Platform trampoline signatures must only be rendered from this model. The
 * application-facing [Type] is deliberately retained separately and is used
 * only when the claimed callback is invoked.
 */
sealed interface KotlinCallbackCAbiType {
    val jvmLayout: String
    val jvmCarrier: String
    val nativeCarrier: String

    data class Scalar(
        val kind: Kind,
        val unsigned: Boolean,
    ) : KotlinCallbackCAbiType {
        enum class Kind(
            val jvmLayout: String,
            val jvmCarrier: String,
            val signedNativeCarrier: String,
            val unsignedNativeCarrier: String = signedNativeCarrier,
        ) {
            BOOL("ValueLayout.JAVA_BOOLEAN", "Boolean", "Boolean"),
            I8("ValueLayout.JAVA_BYTE", "Byte", "Byte", "UByte"),
            I16("ValueLayout.JAVA_SHORT", "Short", "Short", "UShort"),
            I32("ValueLayout.JAVA_INT", "Int", "Int", "UInt"),
            I64("ValueLayout.JAVA_LONG", "Long", "Long", "ULong"),
            CHAR16("ValueLayout.JAVA_CHAR", "Char", "UShort"),
            F32("ValueLayout.JAVA_FLOAT", "Float", "Float"),
            F64("ValueLayout.JAVA_DOUBLE", "Double", "Double"),
        }

        override val jvmLayout: String = kind.jvmLayout
        override val jvmCarrier: String = kind.jvmCarrier

        override val nativeCarrier: String
            get() = if (unsigned) kind.unsignedNativeCarrier else kind.signedNativeCarrier
    }

    data class StructValue(
        val declaration: Declaration.Scoped,
    ) : KotlinCallbackCAbiType {
        override val jvmLayout: String
            get() = "${JavaName.getFullNameOrThrow(declaration)}.layout"
        override val jvmCarrier: String = "MemorySegment"
        override val nativeCarrier: String
            get() = "CValue<webgpu.native.${declaration.name()}>"
    }

    data class Address(
        /** Number of C pointer indirections preserved through typedefs. */
        val pointerDepth: Int,
    ) : KotlinCallbackCAbiType {
        override val jvmLayout: String = "ValueLayout.ADDRESS"
        override val jvmCarrier: String = "MemorySegment"
        override val nativeCarrier: String = "COpaquePointer?"
    }

    companion object {
        fun from(type: Type): KotlinCallbackCAbiType = normalize(type, unsigned = false)

        private fun normalize(type: Type, unsigned: Boolean): KotlinCallbackCAbiType = when {
            type.isErroneous() -> Address(pointerDepth = 1)
            type is Type.Primitive -> scalar(type.kind(), unsigned)
            type is Type.Declared && type.isEnum() -> {
                val underlying = requireNotNull(ClangEnumType.get(type.tree())) {
                    "Callback enum ${type.tree().name()} has no Clang underlying type"
                }
                normalize(underlying, unsigned = false)
            }
            type is Type.Declared && type.isStructOrUnion() -> StructValue(type.tree())
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER ->
                Address(pointerDepth = pointerDepth(type))
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.UNSIGNED ->
                normalize(type.type(), unsigned = true)
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.SIGNED ->
                normalize(type.type(), unsigned = false)
            type is Type.Delegated -> normalize(type.type(), unsigned)
            type is Type.Function || type is Type.Array -> Address(pointerDepth = 1)
            else -> throw UnsupportedOperationException("Unsupported callback C ABI type: $type")
        }

        private fun scalar(kind: Type.Primitive.Kind, unsigned: Boolean): Scalar = when (kind) {
            Type.Primitive.Kind.Bool -> Scalar(Scalar.Kind.BOOL, unsigned = false)
            Type.Primitive.Kind.Char -> Scalar(Scalar.Kind.I8, unsigned)
            Type.Primitive.Kind.Char16 -> Scalar(Scalar.Kind.CHAR16, unsigned = true)
            Type.Primitive.Kind.Short -> Scalar(Scalar.Kind.I16, unsigned)
            Type.Primitive.Kind.Int -> Scalar(Scalar.Kind.I32, unsigned)
            Type.Primitive.Kind.Long ->
                Scalar(if (TypeImpl.IS_WINDOWS) Scalar.Kind.I32 else Scalar.Kind.I64, unsigned)
            Type.Primitive.Kind.LongLong -> Scalar(Scalar.Kind.I64, unsigned)
            Type.Primitive.Kind.Float -> Scalar(Scalar.Kind.F32, unsigned = false)
            Type.Primitive.Kind.Double -> Scalar(Scalar.Kind.F64, unsigned = false)
            Type.Primitive.Kind.LongDouble -> {
                if (!TypeImpl.IS_WINDOWS) unsupportedScalar(kind)
                Scalar(Scalar.Kind.F64, unsigned = false)
            }
            else -> unsupportedScalar(kind)
        }

        private fun unsupportedScalar(kind: Type.Primitive.Kind): Nothing =
            throw UnsupportedOperationException("Unsupported callback C ABI scalar: $kind")

        private fun pointerDepth(type: Type): Int = when {
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER ->
                1 + pointerDepth(type.type())
            type is Type.Delegated -> pointerDepth(type.type())
            else -> 0
        }
    }
}
