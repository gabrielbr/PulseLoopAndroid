package com.pulseloop.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen, widget and component sets type on [PulseType.SCALE] only (design system: sizes
 * come from the layer's type roles, nothing that carries meaning under 12sp). Sixteen sizes in
 * 1sp steps had crept in (10, 11, 12, 13, 14, 15…), which no one can tell apart in a monospace
 * face. This used to cover the main screens only; since the design-system audit of v2.11.56 it
 * covers every Kotlin source, so a new 13sp can't slip back in anywhere.
 */
class PulseTypeScaleTest {
    private fun root(): File {
        val rel = "src/main/java/com/pulseloop"
        return listOf(File(rel), File("app/$rel")).first { it.exists() }
    }

    @Test
    fun everySourceUsesOnlySizesOnTheScale() {
        val literal = Regex("""fontSize = (\d+)\.sp""")
        val base = root()
        val off = base.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                literal.findAll(line).map { it.groupValues[1].toInt() }
                    .filter { it !in PulseType.SCALE }
                    .map { "${file.relativeTo(base)}:${i + 1} uses ${it}sp" }
                    .toList().takeIf { it.isNotEmpty() }
            }.flatten()
        }.toList()
        assertTrue("Sizes off the type scale ${PulseType.SCALE}:\n" + off.joinToString("\n"), off.isEmpty())
    }
}
