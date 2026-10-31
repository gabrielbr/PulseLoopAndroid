package com.pulseloop.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The main screens set type on [PulseType.SCALE] only. Sixteen sizes in 1sp steps had crept in
 * (10, 11, 12, 13, 14, 15…), which no one can tell apart in a monospace face; this keeps a new
 * 13sp from slipping back in unnoticed.
 */
class PulseTypeScaleTest {
    private val mainScreens = listOf(
        "ui/PulseLoopApp.kt",
        "ui/components/AppChrome.kt",
        "ui/components/TodayTiles.kt",
        "ui/components/VitalCard.kt",
        "ui/components/Charts.kt",
        "ui/components/ZoneLineChart.kt",
        "ui/screens/TodayScreen.kt",
        "ui/screens/Screens.kt",
        "ui/screens/ActivityScreen.kt",
        "ui/screens/SleepScreen.kt",
        "ui/screens/NutritionScreen.kt",
        "ui/screens/MyHealthUi.kt",
        "ui/screens/LogSheet.kt",
        "ui/screens/ReadinessUi.kt",
    )

    private fun source(path: String): File {
        val rel = "src/main/java/com/pulseloop/$path"
        return listOf(File(rel), File("app/$rel")).first { it.exists() }
    }

    @Test
    fun mainScreensUseOnlySizesOnTheScale() {
        val literal = Regex("""fontSize = (\d+)\.sp""")
        val off = mainScreens.flatMap { path ->
            source(path).readLines().mapIndexedNotNull { i, line ->
                literal.findAll(line).map { it.groupValues[1].toInt() }
                    .filter { it !in PulseType.SCALE }
                    .map { "$path:${i + 1} uses ${it}sp" }
                    .toList().takeIf { it.isNotEmpty() }
            }.flatten()
        }
        assertTrue("Sizes off the type scale ${PulseType.SCALE}:\n" + off.joinToString("\n"), off.isEmpty())
    }
}
