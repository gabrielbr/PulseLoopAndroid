package com.pulseloop.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every button goes through [PulseButton], which holds the design system's button rules
 * (52dp minimum height, the 97% press scale). A Material `Button`, `FilledTonalButton`,
 * `OutlinedButton` or `TextButton` called directly would ship at Material's 40dp again.
 * Glance widgets are exempt: their `Button` is a different component with its own sizing.
 */
class PulseButtonTest {
    private fun root(): File {
        val rel = "src/main/java/com/pulseloop"
        return listOf(File(rel), File("app/$rel")).first { it.exists() }
    }

    @Test
    fun noMaterialButtonOutsidePulseButton() {
        val call = Regex("""(?<![A-Za-z0-9_@.])(Button|FilledTonalButton|OutlinedButton|TextButton)\(""")
        val qualified = Regex("""androidx\.compose\.material3\.(Button|FilledTonalButton|OutlinedButton|TextButton)\(""")
        val base = root()
        val off = base.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "PulseButton.kt" }
            .filterNot { f -> f.readText().contains("import androidx.glance.Button") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trimStart()
                    if (t.startsWith("import") || t.startsWith("//") || t.startsWith("*")) return@mapIndexedNotNull null
                    if (call.containsMatchIn(line) || qualified.containsMatchIn(line)) "${file.relativeTo(base)}:${i + 1}" else null
                }
            }.toList()
        assertTrue("Use PulseButton instead of a Material button:\n" + off.joinToString("\n"), off.isEmpty())
    }

    @Test
    fun minimumHeightIsTheSystemsButtonSize() {
        assertTrue(PulseButtonDefaults.MinHeight.value == 52f)
    }
}
