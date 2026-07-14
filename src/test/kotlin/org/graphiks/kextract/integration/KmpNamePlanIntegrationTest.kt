package org.graphiks.kextract.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class KmpNamePlanIntegrationTest : FreeSpec({
    "KMP names are deterministic and avoid runtime and synthetic-member collisions" {
        val header =
            """
            typedef struct NativeAddress {
                int value;
            } NativeAddress;

            typedef struct CollisionRecord {
                int handler;
                int layout;
                NativeAddress address;
            } CollisionRecord;
            """.trimIndent()

        val first = generateKmpSources(header)
        val second = generateKmpSources(header)

        first shouldBe second
        first.common shouldContain "import io.ygdrasil.kffi.NativeAddress as KffiNativeAddress"
        first.common shouldContain "expect interface NativeAddress"
        first.common shouldContain "var handler_2: Int"
        first.common shouldContain "var layout_2: Int"
        first.common shouldContain "val handler: KffiNativeAddress"
        first.jvm shouldContain "actual var handler_2: Int"
        first.native shouldContain "actual var handler_2: Int"

        compileAndInvokeGeneratedKmpJvm(
            generated = first,
            probePackage = "sample.probe",
            probeSource =
                """
                package sample.probe

                import sample.bindings.CollisionRecord
                import sample.bindings.NativeAddress

                fun verifyNames(): Array<Class<*>> = arrayOf(
                    CollisionRecord::class.java,
                    NativeAddress::class.java,
                )
                """.trimIndent(),
            facadeClassName = "ProbeKt",
            methodName = "verifyNames",
        )
    }

    "runtime classifiers are aliased whenever a C classifier shadows them" {
        val runtimeSymbolClass = Class.forName("org.graphiks.kextract.kotlin.KotlinKmpRuntimeSymbol")
        val preferredName = runtimeSymbolClass.getMethod("getPreferredName")
        val qualifiedName = runtimeSymbolClass.getMethod("getQualifiedName")
        val sourceSets = runtimeSymbolClass.getMethod("getSourceSets")
        val cases = runtimeSymbolClass.enumConstants.mapNotNull { symbol ->
            val preferred = preferredName.invoke(symbol) as String
            if (!preferred.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return@mapNotNull null
            val sourceSetNames = (sourceSets.invoke(symbol) as Set<*>)
                .mapTo(mutableSetOf()) { (it as Enum<*>).name }
            val sourceSet = listOf(SourceSet.JVM, SourceSet.NATIVE, SourceSet.COMMON)
                .firstOrNull { it.name in sourceSetNames }
                ?: return@mapNotNull null
            RuntimeImportCase(qualifiedName.invoke(symbol) as String, sourceSet)
        }
        val header =
            cases.joinToString("\n") { case ->
                "typedef struct ${case.preferredName} { int value; } ${case.preferredName};"
            } + "\ntypedef struct RuntimeAliasExercise { reinterpret nested; } RuntimeAliasExercise;"
        val generated = generateKmpSources(header)

        cases.forEach { case ->
            val source = case.sourceSet.source(generated)
            val alias = "Kffi${case.preferredName}"
            source shouldContain "import ${case.qualifiedName} as $alias"
            source shouldNotContain "import ${case.qualifiedName}\n"
        }
        generated.native shouldContain ".Kffireinterpret<"
        generated.native shouldContain ".Kffipointed"
        generated.native shouldContain ".Kffiptr"
        generated.native shouldContain ".KffiuseContents {"
    }
})

private data class RuntimeImportCase(
    val qualifiedName: String,
    val sourceSet: SourceSet,
) {
    val preferredName: String = qualifiedName.substringAfterLast('.')
}

private enum class SourceSet {
    COMMON,
    JVM,
    NATIVE;

    fun source(generated: GeneratedKmpSources): String = when (this) {
        COMMON -> generated.common
        JVM -> generated.jvm
        NATIVE -> generated.native
    }
}
