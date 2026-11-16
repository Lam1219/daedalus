package com.medicina.daedaluswatch.pilot

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Review round 009, #4: no unused imports left in RoundSevenFixesTest. */
class RoundNineFixesTest {
    @Test
    fun n4_noUnusedImportsInRoundSevenFixesTest() {
        val src = File("src/test/java/com/medicina/daedaluswatch/pilot/RoundSevenFixesTest.kt").readText()
        assertFalse(src.contains("import org.junit.Assert.assertEquals"))
        assertFalse(src.contains("import java.nio.file.Files"))
    }
}
