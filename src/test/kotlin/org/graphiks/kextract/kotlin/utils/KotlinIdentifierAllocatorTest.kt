package org.graphiks.kextract.kotlin.utils

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class KotlinIdentifierAllocatorTest : FreeSpec({
    "allocates valid identifiers without colliding with reserved or previously allocated names" {
        val names = KotlinIdentifierAllocatorFixture(listOf("callback", "failure"))

        names.allocate("class", "arg0") shouldBe "class_"
        names.allocate("foo\$bar", "arg1") shouldBe "foo_bar"
        names.allocate("foo_bar", "arg2") shouldBe "foo_bar_2"
        names.allocate("callback", "arg3") shouldBe "callback_2"
        names.allocate("", "arg4") shouldBe "arg4"
    }
})

private class KotlinIdentifierAllocatorFixture(reserved: Iterable<String>) {
    private val allocatorClass = Class.forName(
        "org.graphiks.kextract.kotlin.utils.KotlinIdentifierAllocator",
    )
    private val allocator = allocatorClass
        .getDeclaredConstructor(Iterable::class.java)
        .newInstance(reserved)
    private val allocate = allocatorClass.getDeclaredMethod(
        "allocate",
        String::class.java,
        String::class.java,
    )

    fun allocate(rawName: String, fallback: String): String =
        allocate.invoke(allocator, rawName, fallback) as String
}
