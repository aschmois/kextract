package org.graphiks.kextract.kotlin.utils

internal class KotlinIdentifierAllocator(reserved: Iterable<String> = emptyList()) {
    private val used = reserved.mapTo(linkedSetOf(), KotlinNameMangler::mangle)

    fun allocate(rawName: String, fallback: String): String {
        val base = KotlinNameMangler.mangle(rawName).ifBlank {
            KotlinNameMangler.mangle(fallback).ifBlank { "generated" }
        }
        if (used.add(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "${base}_${suffix++}"
            if (used.add(candidate)) return candidate
        }
    }
}
