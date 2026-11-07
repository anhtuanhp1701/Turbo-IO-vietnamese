package org.turboio.customai.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class InterpreterTest {
    @Test fun parsesModelReplyEvenWithFences() {
        val r = InterpreterPrompt.parse("```json\n{\"heard\":\"Where is the station?\",\"language\":\"en-US\",\"translation\":\"Nhà ga ở đâu?\"}\n```")
        assertNotNull(r)
        assertEquals("Where is the station?", r!!.heard)
        assertEquals("Nhà ga ở đâu?", r.translation)
        assertNull(InterpreterPrompt.parse("{\"heard\":\"\"}"))
        assertNull(InterpreterPrompt.parse("no json here"))
    }

    @Test fun decidesDirectionByLanguage() {
        val mine = InterpreterPrompt.Result("Ga ở đâu?", "vi-VN", "Where is the station?")
        val theirs = InterpreterPrompt.Result("Over there.", "en", "Ở đằng kia.")
        assertTrue(InterpreterPrompt.fromMe(mine, "vi-VN", "en-US"))
        assertFalse(InterpreterPrompt.fromMe(theirs, "vi-VN", "en-US"))
        // No tag from the model: the script decides.
        assertTrue(InterpreterPrompt.fromMe(mine.copy(language = ""), "vi-VN", "en-US"))
        assertFalse(InterpreterPrompt.fromMe(InterpreterPrompt.Result("你好", "", "Xin chào"), "vi-VN", "zh-CN"))
    }

    @Test fun promptNamesBothLanguagesAndContext() {
        val p = InterpreterPrompt.system("vi-VN", "ja-JP", listOf(InterpreterLine(0, true, "Xin chào", "こんにちは", "vi-VN")))
        assertTrue(p.contains("Vietnamese")); assertTrue(p.contains("Japanese")); assertTrue(p.contains("Xin chào => こんにちは"))
    }

    @Test fun segmenterCutsAtPause() {
        val seg = UtteranceSegmenter(silenceMs = 800)
        fun block(loud: Boolean, index: Int) = ShortArray(320) { i -> if (loud) (6000 * sin(2 * PI * 220 * (index * 320 + i) / 16000.0)).toInt().toShort() else 0 }
        val outputs = mutableListOf<ByteArray>()
        var n = 0
        repeat(25) { seg.feed(block(false, n++))?.let(outputs::add) }
        repeat(75) { seg.feed(block(true, n++))?.let(outputs::add) }
        repeat(60) { seg.feed(block(false, n++))?.let(outputs::add) }
        assertEquals(1, outputs.size)
        val seconds = outputs[0].size / 32000.0
        assertTrue("duration $seconds", seconds in 2.0..3.0)
    }

    @Test fun segmenterIgnoresSilence() {
        val seg = UtteranceSegmenter()
        repeat(400) { assertNull(seg.feed(ShortArray(320))) }
    }
}
