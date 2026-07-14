package org.graphiks.kextract.kotlin.builders

import org.graphiks.kextract.Declaration

internal data class KotlinJvmRecordMemberLayout(
    val field: Declaration.Variable,
    val kotlinName: String,
    val cName: String,
    val offsetBytes: Long,
    val sizeBytes: Long,
    val alignmentBytes: Long,
    val layoutExpression: String,
)

internal data class KotlinJvmRecordLayout(
    val declaration: Declaration.Scoped,
    val sizeBytes: Long,
    val alignmentBytes: Long,
    val members: List<KotlinJvmRecordMemberLayout>,
) {
    fun render(builder: SourceBuilder) {
        val memoryLayout = "java.lang.foreign.MemoryLayout"
        val layoutElements = when (declaration.kind()) {
            Declaration.Scoped.Kind.STRUCT -> structLayoutElements(memoryLayout)
            Declaration.Scoped.Kind.UNION -> members.map { member ->
                "${member.layoutExpression}.withName(\"${member.cName}\")"
            }
            else -> error("Expected struct or union, found ${declaration.kind()}")
        }
        val factory = when (declaration.kind()) {
            Declaration.Scoped.Kind.STRUCT -> "structLayout"
            Declaration.Scoped.Kind.UNION -> "unionLayout"
            else -> error("Expected struct or union, found ${declaration.kind()}")
        }

        builder.appendLine("val layout: java.lang.foreign.GroupLayout = $memoryLayout.$factory(")
        builder.indent()
        layoutElements.forEachIndexed { index, expression ->
            val comma = if (index < layoutElements.lastIndex) "," else ""
            builder.appendLine("$expression$comma")
        }
        builder.unindent()
        builder.appendLine(
            ").withByteAlignment($alignmentBytes).withName(\"${declaration.name()}\")",
        )
    }

    private fun structLayoutElements(memoryLayout: String): List<String> = buildList {
        var previousEnd = 0L
        members.forEach { member ->
            val gap = member.offsetBytes - previousEnd
            if (gap > 0L) add("$memoryLayout.paddingLayout($gap)")
            add("${member.layoutExpression}.withName(\"${member.cName}\")")
            previousEnd = member.offsetBytes + member.sizeBytes
        }
        val finalPadding = sizeBytes - previousEnd
        if (finalPadding > 0L) add("$memoryLayout.paddingLayout($finalPadding)")
    }
}
