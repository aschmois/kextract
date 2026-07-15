package org.graphiks.kextract.kotlin.abi

import org.graphiks.kextract.Declaration
import org.graphiks.kextract.DeclarationImpl.Skip
import org.graphiks.kextract.Type
import java.util.IdentityHashMap

internal class KotlinKmpAbiIndex private constructor(
    private val enums: IdentityHashMap<Declaration.Scoped, KotlinKmpCAbiType.Scalar>,
) {
    fun enum(declaration: Declaration.Scoped): KotlinKmpCAbiType.Scalar =
        requireNotNull(enums[declaration]) {
            "No KMP ABI carrier was planned for enum ${declaration.name()}"
        }

    companion object {
        fun create(scoped: Declaration.Scoped): KotlinKmpAbiIndex {
            val enums = IdentityHashMap<Declaration.Scoped, KotlinKmpCAbiType.Scalar>()

            fun collect(declaration: Declaration) {
                if (Skip.isPresent(declaration)) return
                if (declaration !is Declaration.Scoped) return
                if (declaration.kind() == Declaration.Scoped.Kind.ENUM) {
                    val abiType = KotlinKmpCAbiType.from(
                        Type.declared(declaration),
                        KotlinKmpAbiContext.FIELD,
                    )
                    enums[declaration] = abiType as? KotlinKmpCAbiType.Scalar
                        ?: error("Enum ${declaration.name()} must have a scalar C ABI type")
                }
                declaration.members().forEach(::collect)
            }

            collect(scoped)
            return KotlinKmpAbiIndex(enums)
        }
    }
}
