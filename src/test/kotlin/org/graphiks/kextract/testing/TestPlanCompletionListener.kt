package org.graphiks.kextract.testing

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import java.nio.file.Files
import java.nio.file.Path

class TestPlanCompletionListener : TestExecutionListener {
    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        completionMarker()?.let(Files::deleteIfExists)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        val marker = completionMarker() ?: return
        val testCount = testPlan.countTestIdentifiers { it.isTest }
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "tests=$testCount\n")
    }

    private fun completionMarker(): Path? =
        System.getProperty("kextract.testCompletionMarker")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
}
