package com.example

import com.example.data.PresetLibrary
import com.example.model.CanvasRatio
import com.example.model.TextTransitionEffect
import com.example.model.VisualizerType
import com.example.model.parseColorHex
import org.junit.Assert.*
import org.junit.Test

class LyricMakerUnitTest {

    @Test
    fun defaultProject_hasValidLyricsAndAudio() {
        val project = PresetLibrary.createDefaultProject()
        assertNotNull(project)
        assertFalse(project.lyrics.isEmpty())
        assertTrue(project.durationMs > 0)
        assertNotNull(project.audioTrack)
        assertTrue(project.audioTrack.bpm in 60..180)
    }

    @Test
    fun parseLrc_extractsCorrectTimestamps() {
        val lrcInput = """
            [00:02.50] Shining bright in neon rain
            [00:07.10] Finding hope inside the pain
            [00:12.80] Never turn around again
        """.trimIndent()

        val parsed = PresetLibrary.parseLrcOrText(lrcInput)
        assertEquals(3, parsed.size)
        assertEquals("Shining bright in neon rain", parsed[0].text)
        assertEquals(2500L, parsed[0].startMs)
        assertEquals(7100L, parsed[0].endMs)

        assertEquals("Finding hope inside the pain", parsed[1].text)
        assertEquals(7100L, parsed[1].startMs)
        assertEquals(12800L, parsed[1].endMs)

        assertEquals("Never turn around again", parsed[2].text)
        assertEquals(12800L, parsed[2].startMs)
    }

    @Test
    fun parsePlainText_autoAssignsDurations() {
        val textInput = """
            Line one of the song
            Line two of the song
        """.trimIndent()

        val parsed = PresetLibrary.parseLrcOrText(textInput)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].startMs < parsed[0].endMs)
        assertTrue(parsed[1].startMs >= parsed[0].endMs)
    }

    @Test
    fun exportToLrc_formatsCorrectly() {
        val line = PresetLibrary.createLyricLineWithWords("Dancing in the rain", 65500L, 70000L)
        val lrc = PresetLibrary.exportToLrc(listOf(line))
        assertTrue(lrc.contains("[01:05.50] Dancing in the rain"))
    }

    @Test
    fun canvasRatios_haveValidDimensions() {
        CanvasRatio.entries.forEach { ratio ->
            assertTrue(ratio.exportWidth > 0)
            assertTrue(ratio.exportHeight > 0)
            assertTrue(ratio.aspectRatio > 0f)
        }
    }

    @Test
    fun parseColorHex_handlesColorsSafely() {
        val cyan = parseColorHex("#00F5D4")
        assertNotNull(cyan)

        val fallback = parseColorHex("invalid_hex")
        assertNotNull(fallback)
    }
}
