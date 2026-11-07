package org.turboio.customai.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonioxTest {
    @Test fun configIsTwoWayWithContextAndGlossary() {
        val c = JSONObject(SonioxWire.config("", "vi-VN", "zh-CN", "Đàm phán đơn hàng vải với nhà cung cấp Quảng Châu",
            "张总 = Giám đốc Trương\n面料 → vải\n订单", 800))
        assertEquals("stt-rt-v6", c.getString("model"))
        assertEquals("pcm_s16le", c.getString("audio_format")); assertEquals(16000, c.getInt("sample_rate"))
        val t = c.getJSONObject("translation")
        assertEquals("two_way", t.getString("type")); assertEquals("vi", t.getString("language_a")); assertEquals("zh", t.getString("language_b"))
        val ctx = c.getJSONObject("context")
        assertTrue(ctx.getString("text").startsWith("Đàm phán"))
        val pairs = ctx.getJSONArray("translation_terms")
        assertEquals(2, pairs.length()); assertEquals("张总", pairs.getJSONObject(0).getString("source")); assertEquals("vải", pairs.getJSONObject(1).getString("target"))
        assertTrue(ctx.getJSONArray("terms").toString().contains("订单"))
        assertTrue(c.getBoolean("enable_endpoint_detection"))
    }

    @Test fun listenModeIsOneWayIntoMyLanguageWithBroadHints() {
        val c = JSONObject(SonioxWire.config("", "vi-VN", "zh-CN", "", "", 800, listenMode = true))
        val t = c.getJSONObject("translation")
        assertEquals("one_way", t.getString("type")); assertEquals("vi", t.getString("target_language"))
        val hints = c.getJSONArray("language_hints").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(hints.containsAll(listOf("zh", "en", "vi")))
        assertTrue(c.getBoolean("enable_speaker_diarization"))
    }

    @Test fun utterancesCarrySpeaker() {
        val u = SonioxUtterances()
        u.accept(listOf(SonioxWire.Token("你好", true, false, "zh", "2")))
        val ended = u.accept(listOf(SonioxWire.Token("Xin chào", true, true, "vi", "2"), SonioxWire.Token("<end>", true, false, "", "2"))).first { it.ended }
        assertEquals("2", ended.speaker); assertEquals("你好", ended.original); assertEquals("Xin chào", ended.translation)
    }

    private fun tok(text: String, final: Boolean, tr: Boolean = false, lang: String = "zh") = SonioxWire.Token(text, final, tr, lang, "1")

    @Test fun utterancesStreamThenEndAtEndpoint() {
        val u = SonioxUtterances()
        val a = u.accept(listOf(tok("这个", false))).single()
        assertEquals("这个", a.original); assertFalse(a.ended); assertEquals("zh", a.language)
        val b = u.accept(listOf(tok("这个面料", true), tok("Loại vải", false, tr = true, lang = "vi"))).single()
        assertEquals("这个面料", b.original); assertEquals("Loại vải", b.translation)
        val c = u.accept(listOf(tok("Loại vải này", true, tr = true, lang = "vi"), tok("<end>", true)))
        val ended = c.first { it.ended }
        assertEquals("这个面料", ended.original); assertEquals("Loại vải này", ended.translation); assertEquals("zh", ended.language)
        val next = u.accept(listOf(tok("Giá", false, lang = "vi"))).single()
        assertEquals("Giá", next.original); assertEquals("vi", next.language)
    }

    @Test fun parsesTokensAndErrors() {
        val m = SonioxWire.parse("""{"tokens":[{"text":"你好","is_final":true,"language":"zh","translation_status":"original"},
            {"text":"Xin chào","is_final":false,"language":"vi","translation_status":"translation"}],"final_audio_proc_ms":800}""")
        assertEquals(2, m.tokens.size); assertTrue(m.tokens[1].translation); assertFalse(m.tokens[1].final)
        assertNotNull(SonioxWire.parse("""{"error_code":401,"error_type":"unauthenticated","error_message":"bad key"}""").error)
        assertTrue(SonioxWire.parse("""{"tokens":[],"finished":true}""").finished)
    }
}
