package org.turboio.customai.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveWireTest {
    @Test fun setupAsksForOneTargetWithoutEcho() {
        val setup = JSONObject(LiveWire.setup("gemini-3.5-live-translate-preview", "vi-VN")).getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        val gen = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals("vi", gen.getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertFalse(gen.getJSONObject("translationConfig").getBoolean("echoTargetLanguage"))
        assertTrue(setup.has("inputAudioTranscription") && setup.has("outputAudioTranscription"))
    }

    @Test fun audioIsBase64Pcm16k() {
        val a = JSONObject(LiveWire.audio(byteArrayOf(1, 2, 3, 4))).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", a.getString("mimeType"))
        assertEquals(listOf<Byte>(1, 2, 3, 4), Base64.getDecoder().decode(a.getString("data")).toList())
    }

    @Test fun parsesServerContent() {
        val audio = Base64.getEncoder().encodeToString(ByteArray(8))
        val events = LiveWire.parse("""{"serverContent":{"inputTranscription":{"text":"Where is"},"outputTranscription":{"text":"Ở đâu"},
            "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audio"}}]},"turnComplete":true}}""")
        assertEquals(LiveWire.Event.Input("Where is"), events[0])
        assertEquals(LiveWire.Event.Output("Ở đâu"), events[1])
        assertTrue(events[2] is LiveWire.Event.Audio && (events[2] as LiveWire.Event.Audio).pcm24k.size == 8)
        assertEquals(LiveWire.Event.TurnComplete, events.last())
        assertEquals(listOf(LiveWire.Event.SetupComplete), LiveWire.parse("""{"setupComplete":{}}"""))
        assertTrue(LiveWire.parse("""{"serverContent":{"interrupted":true}}""").single() is LiveWire.Event.Interrupted)
        assertTrue(LiveWire.parse("not json").isEmpty())
    }

    @Test fun captionTailKeepsNewestWords() {
        assertEquals("ngắn", LiveWire.tail("  ngắn "))
        val long = (1..40).joinToString(" ") { "từ$it" }
        val t = LiveWire.tail(long, 40)
        assertTrue(t, t.startsWith("…") && t.endsWith("từ40") && t.length <= 41 && !t.contains("từ1 "))
    }

    @Test fun languageCodes() {
        assertEquals("en", LiveWire.languageCode("en-US")); assertEquals("zh", LiveWire.languageCode("zh-CN"))
    }
}
