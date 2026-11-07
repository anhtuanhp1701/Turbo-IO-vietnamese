package org.turboio.customai.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.stt.DeepgramSpeechTranscriber

class SpeechTextTest {
    @Test fun guessesLanguageFromLetters() {
        assertEquals("vi-VN", SpeechText.guessLanguage("Hôm nay trời đẹp quá.", "vi-VN"))
        assertEquals("en-US", SpeechText.guessLanguage("Apple is a fruit.", "vi-VN"))
        assertEquals("vi-VN", SpeechText.guessLanguage("OK", "vi-VN"))
        assertEquals("zh-CN", SpeechText.guessLanguage("你好", "vi-VN"))
        assertEquals("ja-JP", SpeechText.guessLanguage("ありがとう", "vi-VN"))
        assertEquals("ko-KR", SpeechText.guessLanguage("안녕하세요", "vi-VN"))
    }

    @Test fun splitsMixedAnswerByLanguage() {
        val chunks = SpeechText.chunks("Quả táo tiếng Anh là apple. Apple is a fruit. Còn quả cam là orange.", "vi-VN")
        assertEquals(listOf("vi-VN", "en-US", "vi-VN"), chunks.map { it.language })
        assertEquals("Apple is a fruit.", chunks[1].text)
    }

    @Test fun groupsSameLanguageAndCapsLength() {
        val long = (1..40).joinToString(" ") { "Câu số $it rất dài." }
        val chunks = SpeechText.chunks(long, "vi-VN", maxChars = 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 120 && it.language == "vi-VN" })
    }

    @Test fun removesWhatShouldNotBeRead() {
        assertEquals("Xem Wiki để biết thêm", SpeechText.forSpeech("**Xem** [Wiki](https://vi.wikipedia.org) để biết thêm 😀"))
    }

    @Test fun readsWavOrRawPcm() {
        val samples = ByteArray(320) { it.toByte() }
        val (pcm, rate) = SpeechText.pcm(DeepgramSpeechTranscriber.wav(samples))
        assertArrayEquals(samples, pcm); assertEquals(16000, rate)
        val (raw, rawRate) = SpeechText.pcm(samples)
        assertArrayEquals(samples, raw); assertEquals(24000, rawRate)
    }
}
