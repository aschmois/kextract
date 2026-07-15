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

    "ByValue is reserved for generated Native and Android wrapper classes" {
        val generated = generateKmpSources(
            """
            typedef struct ValueCollision {
                int ByValue;
            } ValueCollision;
            """.trimIndent(),
        )

        generated.common shouldContain "var ByValue_2: Int"
        generated.native shouldContain "actual var ByValue_2: Int"
        generated.native shouldContain "value class ByValue("
        generated.android shouldContain "actual var ByValue_2: Int"
        generated.android shouldContain "class ByValue("
    }

    "Android raw JNA helper names avoid raw C field names" {
        val generated = generateKmpSources(
            """
            typedef struct JnaHelperCollision {
                int ByReference;
                int ByValue;
            } JnaHelperCollision;
            """.trimIndent(),
        )

        generated.android shouldContain "@JvmField var ByReference: Int = 0"
        generated.android shouldContain "@JvmField var ByValue: Int = 0"
        generated.android shouldContain
            "class ByReference_2(pointer: Pointer? = null) : JnaHelperCollision(pointer), Structure.ByReference"
        generated.android shouldContain
            "class ByValue_2(pointer: Pointer? = null) : JnaHelperCollision(pointer), Structure.ByValue"
        generated.android shouldContain
            "sample.bindings.android.JnaHelperCollision.ByReference_2(address)"
        generated.android shouldContain
            "sample.bindings.android.JnaHelperCollision.ByValue_2()"
    }

    "KMP declarations and parameters are Kotlin-safe before emission" {
        val generated = generateKmpSources(
            """
            typedef struct class {
                int when;
                int when_;
            } class;

            typedef enum sealed {
                when = 1,
                when_ = 2
            } sealed;

            int fun(class class, int when, int when_);
            int fun_(int value);
            """.trimIndent(),
        )

        compileAndInvokeGeneratedKmpJvm(
            generated = generated,
            probePackage = "sample.probe",
            probeSource =
                """
                package sample.probe

                import sample.bindings.class_

                fun verifyKeywordNames(): Class<*> = class_::class.java
                """.trimIndent(),
            facadeClassName = "ProbeKt",
            methodName = "verifyKeywordNames",
        )

        generated.common shouldContain "expect interface class_"
        generated.common shouldContain "var when_: Int"
        generated.common shouldContain "var when__2: Int"
        generated.common shouldContain "typealias sealed_"
        generated.common shouldContain "const val when_"
        generated.common shouldContain "const val when__2"
        generated.common shouldContain "expect fun fun_(class_: class_, when_: Int, when__2: Int): Int"
        generated.common shouldContain "expect fun fun__2(value: Int): Int"
        generated.jvm shouldContain "actual fun fun_(class_: class_, when_: Int, when__2: Int): Int"
        generated.native shouldContain "webgpu.native.`fun`("
        generated.native shouldContain "this.`when`"
        generated.android shouldContain "@JvmField var `when`: Int = 0"
        generated.android shouldContain "handle.`when`"
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
            val sourceSet = listOf(SourceSet.JVM, SourceSet.NATIVE, SourceSet.COMMON, SourceSet.ANDROID)
                .firstOrNull { it.name in sourceSetNames }
                ?: return@mapNotNull null
            RuntimeImportCase(qualifiedName.invoke(symbol) as String, sourceSet)
        }
        val header =
            cases.joinToString("\n") { case ->
                "typedef struct ${case.preferredName} { int value; } ${case.preferredName};"
            } + "\ntypedef struct RuntimeAliasExercise { reinterpret nested; } RuntimeAliasExercise;" +
                "\ntypedef union RuntimeAndroidAliasExercise { int value; } RuntimeAndroidAliasExercise;"
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
        generated.android shouldContain
            "open class RuntimeAliasExercise(pointer: KffiPointer? = null) : KffiStructure(pointer)"
        generated.android shouldContain
            "open class RuntimeAndroidAliasExercise(pointer: KffiPointer? = null) : KffiUnion(pointer)"
        generated.android shouldContain "@KffiJvmField var"
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
    NATIVE,
    ANDROID;

    fun source(generated: GeneratedKmpSources): String = when (this) {
        COMMON -> generated.common
        JVM -> generated.jvm
        NATIVE -> generated.native
        ANDROID -> generated.android
    }
}
