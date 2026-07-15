package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.ClangAlignOf
import org.graphiks.kextract.DeclarationImpl.ClangOffsetOf
import org.graphiks.kextract.DeclarationImpl.ClangSizeOf
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import org.graphiks.kextract.kotlin.KotlinKmpNamePlan
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.MEMORY_LAYOUT
import org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol.VALUE_LAYOUT
import org.graphiks.kextract.kotlin.abi.KotlinKmpAbiIndex
import org.graphiks.kextract.pipeline.LayoutUtils
import java.util.IdentityHashMap

internal class KotlinJvmRecordLayoutPlan private constructor(
    private val layouts: IdentityHashMap<Declaration.Scoped, KotlinJvmRecordLayout>,
) {
    operator fun get(declaration: Declaration.Scoped): KotlinJvmRecordLayout =
        requireNotNull(layouts[declaration]) {
            "No JVM record layout was planned for ${declaration.name()}"
        }

    companion object {
        fun create(
            scoped: Declaration.Scoped,
            names: KotlinKmpNamePlan,
            abiIndex: KotlinKmpAbiIndex,
        ): KotlinJvmRecordLayoutPlan {
            val layouts = IdentityHashMap<Declaration.Scoped, KotlinJvmRecordLayout>()

            fun collect(declaration: Declaration) {
                if (declaration !is Declaration.Scoped) return
                if (
                    !Skip.isPresent(declaration) &&
                    declaration.kind() in setOf(Declaration.Scoped.Kind.STRUCT, Declaration.Scoped.Kind.UNION)
                ) {
                    layouts[declaration] = createLayout(
                        declaration,
                        names,
                        abiIndex,
                        recordStack = mutableListOf(),
                    )
                }
                declaration.members().forEach(::collect)
            }

            collect(scoped)
            return KotlinJvmRecordLayoutPlan(layouts)
        }

        private fun createLayout(
            declaration: Declaration.Scoped,
            names: KotlinKmpNamePlan,
            abiIndex: KotlinKmpAbiIndex,
            alignmentCeiling: Long? = null,
            recordStack: MutableList<Declaration.Scoped>,
        ): KotlinJvmRecordLayout {
            val cycleStart = recordStack.indexOfFirst { it === declaration }
            require(cycleStart < 0) {
                val cycle = recordStack.subList(cycleStart, recordStack.size) + declaration
                "JVM record layout has a by-value cycle: ${cycle.joinToString(" -> ") { it.name() }}"
            }
            recordStack.add(declaration)
            try {
                val owner = declaration.name()
                val sizeBytes = bitsToBytes(
                    metric = "size",
                    owner = owner,
                    bits = requireNotNull(ClangSizeOf.get(declaration)) {
                        "$owner has no Clang size"
                    },
                )
                val naturalAlignmentBytes = requireAlignment(
                    owner,
                    bitsToBytes(
                        metric = "alignment",
                        owner = owner,
                        bits = requireNotNull(ClangAlignOf.get(declaration)) {
                            "$owner has no Clang alignment"
                        },
                    ),
                )
                val alignmentBytes = alignmentCeiling
                    ?.let { ceiling -> minOf(naturalAlignmentBytes, requireAlignment(owner, ceiling)) }
                    ?: naturalAlignmentBytes
                val members = declaration.members()
                    .filterIsInstance<Declaration.Variable>()
                    .filterNot(Skip::isPresent)
                    .map { field ->
                        createMemberLayout(
                            declaration,
                            field,
                            sizeBytes,
                            alignmentBytes,
                            names,
                            abiIndex,
                            recordStack,
                        )
                    }

                validateMembers(declaration, sizeBytes, members)
                return KotlinJvmRecordLayout(
                    declaration = declaration,
                    sizeBytes = sizeBytes,
                    alignmentBytes = alignmentBytes,
                    members = members,
                )
            } finally {
                recordStack.removeAt(recordStack.lastIndex)
            }
        }

        private fun createMemberLayout(
            declaration: Declaration.Scoped,
            field: Declaration.Variable,
            recordSizeBytes: Long,
            recordAlignmentBytes: Long,
            names: KotlinKmpNamePlan,
            abiIndex: KotlinKmpAbiIndex,
            recordStack: MutableList<Declaration.Scoped>,
        ): KotlinJvmRecordMemberLayout {
            val owner = "${declaration.name()}.${field.name()}"
            val offsetBytes = bitsToBytes(
                metric = "offset",
                owner = owner,
                bits = requireNotNull(ClangOffsetOf.get(field)) {
                    "$owner has no Clang offset"
                },
            )
            val sizeBytes = bitsToBytes(
                metric = "size",
                owner = owner,
                bits = requireNotNull(ClangSizeOf.get(field)) {
                    "$owner has no Clang size"
                },
            )
            val naturalAlignmentBytes = requireAlignment(
                owner,
                bitsToBytes(
                    metric = "alignment",
                    owner = owner,
                    bits = requireNotNull(ClangAlignOf.get(field)) {
                        "$owner has no Clang alignment"
                    },
                ),
            )
            val alignmentBytes = effectiveMemberAlignment(
                naturalFieldAlignment = naturalAlignmentBytes,
                recordAlignment = recordAlignmentBytes,
                offsetBytes = offsetBytes,
            )
            require(offsetBytes <= recordSizeBytes && sizeBytes <= recordSizeBytes - offsetBytes) {
                "$owner exceeds the record size"
            }

            return KotlinJvmRecordMemberLayout(
                field = field,
                kotlinName = names.member(field),
                cName = field.name(),
                offsetBytes = offsetBytes,
                sizeBytes = sizeBytes,
                alignmentBytes = alignmentBytes,
                layoutExpression = layoutString(field.type(), alignmentBytes, names, abiIndex, recordStack),
            )
        }

        private fun layoutString(
            type: Type,
            byteAlignment: Long,
            names: KotlinKmpNamePlan,
            abiIndex: KotlinKmpAbiIndex,
            recordStack: MutableList<Declaration.Scoped>,
        ): String = when {
            type is Type.Delegated && type.kind() == Type.Delegated.Kind.POINTER ->
                planRuntimeNames(LayoutUtils.layoutString(type, byteAlignment, abiIndex), names)
            type is Type.Delegated -> layoutString(type.type(), byteAlignment, names, abiIndex, recordStack)
            type is Type.Array ->
                "${names.runtime(MEMORY_LAYOUT)}.sequenceLayout(${type.elementCount() ?: 0L}, " +
                    "${layoutString(type.elementType(), byteAlignment, names, abiIndex, recordStack)})" +
                    ".withByteAlignment($byteAlignment)"
            type is Type.Declared && type.tree().kind() in setOf(
                Declaration.Scoped.Kind.STRUCT,
                Declaration.Scoped.Kind.UNION,
            ) -> createLayout(
                declaration = type.tree(),
                names = names,
                abiIndex = abiIndex,
                alignmentCeiling = byteAlignment,
                recordStack = recordStack,
            ).renderExpression()
            else -> planRuntimeNames(LayoutUtils.layoutString(type, byteAlignment, abiIndex), names)
        }

        private fun validateMembers(
            declaration: Declaration.Scoped,
            sizeBytes: Long,
            members: List<KotlinJvmRecordMemberLayout>,
        ) {
            when (declaration.kind()) {
                Declaration.Scoped.Kind.STRUCT -> {
                    var previousEnd = 0L
                    members.forEach { member ->
                        require(member.offsetBytes >= previousEnd) {
                            "${declaration.name()}.${member.cName} overlaps the preceding field"
                        }
                        require(member.offsetBytes + member.sizeBytes <= sizeBytes) {
                            "${declaration.name()}.${member.cName} exceeds the record size"
                        }
                        previousEnd = member.offsetBytes + member.sizeBytes
                    }
                }
                Declaration.Scoped.Kind.UNION -> members.forEach { member ->
                    require(member.offsetBytes == 0L) {
                        "${declaration.name()}.${member.cName} has non-zero union offset: ${member.offsetBytes}"
                    }
                }
                else -> error("Expected struct or union, found ${declaration.kind()}")
            }
        }

        private fun bitsToBytes(metric: String, owner: String, bits: Long): Long {
            require(bits >= 0L) { "$owner has negative $metric: $bits bits" }
            require(bits % 8L == 0L) { "$owner has non-byte-addressable $metric: $bits bits" }
            return bits / 8L
        }

        private fun requireAlignment(owner: String, bytes: Long): Long {
            require(bytes > 0L && bytes.countOneBits() == 1) {
                "$owner has invalid byte alignment: $bytes"
            }
            return bytes
        }

        private fun effectiveMemberAlignment(
            naturalFieldAlignment: Long,
            recordAlignment: Long,
            offsetBytes: Long,
        ): Long {
            val offsetAlignment = if (offsetBytes == 0L) {
                Long.MAX_VALUE
            } else {
                java.lang.Long.lowestOneBit(offsetBytes)
            }
            return minOf(naturalFieldAlignment, recordAlignment, offsetAlignment)
        }

        private fun planRuntimeNames(rendered: String, names: KotlinKmpNamePlan): String =
            listOf(VALUE_LAYOUT, MEMORY_LAYOUT).fold(rendered) { value, symbol ->
                value.replace(symbol.preferredName, names.runtime(symbol))
            }
    }
}
