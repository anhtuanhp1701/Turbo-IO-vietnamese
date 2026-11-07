package org.turboio.customai.translate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.TurboApp
import org.turboio.customai.ai.AiConfig
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import java.util.concurrent.TimeUnit

/** Engines that take a continuous 16 kHz microphone stream. */
interface StreamingInterpreter {
    fun start()
    fun feed(pcm16k: ByteArray)
    /** True while our own translated speech plays (plus [graceMs]): the microphones then send silence. */
    fun gated(graceMs: Long): Boolean
    fun stop()
}

/** Events shared by the streaming engines. */
interface InterpreterEvents {
    /** A caption while the sentence is still forming ([final] false) and once it is complete. */
    fun caption(fromMe: Boolean, heard: String, translation: String, final: Boolean)
    fun note(text: String)
    fun failed(reason: String)
}

/** Soniox real-time wire format (pure, unit-tested). */
object SonioxWire {
    const val URL = "wss://stt-rt.soniox.com/transcribe-websocket"
    const val DEFAULT_MODEL = "stt-rt-v6"

    fun code(tag: String) = tag.substringBefore('-').lowercase()

    /** "nguồn = đích" per line (also "->", "→", "=>"): preferred translations; a line without a separator is a term to recognise. */
    fun glossary(raw: String): Pair<List<String>, List<Pair<String, String>>> {
        val terms = mutableListOf<String>(); val pairs = mutableListOf<Pair<String, String>>()
        for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val sep = Regex("\\s*(=>|->|→|=)\\s*").find(line)
            if (sep == null) { terms.add(line.take(80)); continue }
            val a = line.substring(0, sep.range.first).trim(); val b = line.substring(sep.range.last + 1).trim()
            if (a.isNotEmpty() && b.isNotEmpty()) { pairs.add(a.take(80) to b.take(80)); terms.add(a.take(80)) }
        }
        return terms.distinct().take(300) to pairs.take(300)
    }

    fun config(model: String, my: String, partner: String, contextText: String, glossary: String, endpointDelayMs: Int,
               listenMode: Boolean = false): String {
        val (terms, pairs) = glossary(glossary)
        val context = JSONObject()
        if (contextText.isNotBlank()) context.put("text", contextText.take(4000))
        context.put("general", JSONArray().put(JSONObject().put("key", "setting").put("value", "face-to-face conversation")))
        if (terms.isNotEmpty()) context.put("terms", JSONArray(terms))
        if (pairs.isNotEmpty()) context.put("translation_terms", JSONArray(pairs.map { JSONObject().put("source", it.first).put("target", it.second) }))
        // Listen mode: Soniox identifies whatever is spoken and translates it into my language; the hints
        // only bias towards likely languages (Chinese, English and the two configured ones).
        val hints = LinkedHashSet<String>().apply {
            if (listenMode) { add("zh"); add("en") }
            add(code(my)); add(code(partner))
        }
        val translation = if (listenMode) JSONObject().put("type", "one_way").put("target_language", code(my))
            else JSONObject().put("type", "two_way").put("language_a", code(my)).put("language_b", code(partner))
        return JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("audio_format", "pcm_s16le").put("sample_rate", 16000).put("num_channels", 1)
            .put("language_hints", JSONArray(hints.toList()))
            .put("enable_language_identification", true)
            .put("enable_speaker_diarization", true)
            .put("enable_endpoint_detection", true)
            .put("max_endpoint_delay_ms", endpointDelayMs.coerceIn(500, 3000))
            .put("context", context)
            .put("translation", translation)
            .toString()
    }

    data class Token(val text: String, val final: Boolean, val translation: Boolean, val language: String, val speaker: String)
    data class Message(val tokens: List<Token>, val finished: Boolean, val error: String?)

    fun parse(raw: String): Message {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Message(emptyList(), false, null)
        if (o.has("error_code") || o.has("error_message"))
            return Message(emptyList(), false, "${o.optString("error_code")} ${o.optString("error_type")} ${o.optString("error_message")}".trim())
        val arr = o.optJSONArray("tokens") ?: JSONArray()
        val tokens = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            Token(t.optString("text"), t.optBoolean("is_final"), t.optString("translation_status") == "translation",
                t.optString("language"), t.optString("speaker"))
        }
        return Message(tokens, o.optBoolean("finished"), null)
    }
}

/**
 * Turns Soniox token responses into utterances: final tokens are kept, non-final ones replaced on every response;
 * an `<end>` (endpoint) or `<fin>` (finalize) token closes the utterance. Pure, unit-tested.
 */
class SonioxUtterances {
    data class Update(val original: String, val translation: String, val language: String, val ended: Boolean, val speaker: String = "")

    private val finalOriginal = StringBuilder()
    private val finalTranslation = StringBuilder()
    private val languages = HashMap<String, Int>()
    private val speakers = HashMap<String, Int>()
    private var liveLanguage = ""
    private var liveSpeaker = ""

    fun accept(tokens: List<SonioxWire.Token>): List<Update> {
        val out = mutableListOf<Update>()
        val liveOriginal = StringBuilder(); val liveTranslation = StringBuilder()
        for (t in tokens) {
            if (t.text == "<end>" || t.text == "<fin>") {
                if (finalOriginal.isNotBlank() || finalTranslation.isNotBlank()) out.add(current(true, "", ""))
                reset(); liveOriginal.setLength(0); liveTranslation.setLength(0)
                continue
            }
            if (t.final) {
                if (t.translation) finalTranslation.append(t.text)
                else {
                    finalOriginal.append(t.text)
                    if (t.language.isNotEmpty()) languages.merge(t.language, t.text.length, Int::plus)
                    if (t.speaker.isNotEmpty()) speakers.merge(t.speaker, t.text.length, Int::plus)
                }
            } else {
                if (t.translation) liveTranslation.append(t.text)
                else { liveOriginal.append(t.text); if (t.language.isNotEmpty()) liveLanguage = t.language; if (t.speaker.isNotEmpty()) liveSpeaker = t.speaker }
            }
        }
        if (liveOriginal.isNotEmpty() || liveTranslation.isNotEmpty() || (tokens.isNotEmpty() && finalOriginal.isNotBlank()))
            out.add(current(false, liveOriginal.toString(), liveTranslation.toString()))
        return out
    }

    private fun current(ended: Boolean, liveOriginal: String, liveTranslation: String) = Update(
        (finalOriginal.toString() + liveOriginal).trim(), (finalTranslation.toString() + liveTranslation).trim(),
        languages.maxByOrNull { it.value }?.key ?: liveLanguage, ended,
        speakers.maxByOrNull { it.value }?.key ?: liveSpeaker)

    private fun reset() { finalOriginal.setLength(0); finalTranslation.setLength(0); languages.clear(); speakers.clear(); liveLanguage = ""; liveSpeaker = "" }
}

/**
 * Two-way interpreter on Soniox real-time translation (one WebSocket, both directions, with the user's context and
 * glossary). Captions update on the lenses while the person is still speaking; at each sentence end Gemini can
 * re-translate the sentence with the conversation context for accuracy, then the result is spoken: my sentences on
 * the loudspeaker (for the other person), theirs in my earphones.
 */
class SonioxInterpreter(
    private val app: TurboApp,
    private val settings: InterpreterSettings,
    private val sonioxKey: String,
    private val events: InterpreterEvents,
) : StreamingInterpreter {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val utterances = SonioxUtterances()
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var running = false
    private var failures = 0
    private val pending = java.io.ByteArrayOutputStream()
    @Volatile private var lastSentAt = 0L

    override fun start() { running = true; connect(); events.note("Đang kết nối Soniox…") }

    private fun connect() {
        ready = false
        val request = Request.Builder().url(SonioxWire.URL).header("Authorization", "Bearer $sonioxKey").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(SonioxWire.config(settings.sonioxModel, settings.myLanguage, settings.partnerLanguage,
                    settings.contextText, settings.glossary, settings.silenceMs, settings.listenMode))
                ready = true; failures = 0
                DiagnosticLog.i("DICH", "Soniox: phiên mở (${SonioxWire.code(settings.myLanguage)}⇄${SonioxWire.code(settings.partnerLanguage)})")
                events.note("Đang nghe · nói chuyện bình thường")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed("đóng ($code) $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed(response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    private fun closed(reason: String) {
        ready = false
        if (!running) return
        DiagnosticLog.w("DICH", "Soniox: ${Redactor.redact(reason).take(160)}")
        failures++
        if (reason.contains("401") || reason.contains("402") || reason.contains("403") || failures >= 6) {
            running = false; events.failed(friendly(reason)); return
        }
        events.note("Mất kết nối Soniox; đang kết nối lại…")
        scope.launch { kotlinx.coroutines.delay(700L * failures); if (running) connect() }
    }

    private fun handle(raw: String) {
        val m = SonioxWire.parse(raw)
        if (m.error != null) { DiagnosticLog.w("DICH", "Soniox lỗi: ${Redactor.redact(m.error).take(160)}"); closed(m.error); return }
        for (u in utterances.accept(m.tokens)) {
            if (u.original.isEmpty() && u.translation.isEmpty()) continue
            val fromMe = if (u.language.isNotEmpty()) Languages.same(u.language, settings.myLanguage)
                else Languages.same(org.turboio.customai.tts.SpeechText.guessLanguage(u.original, settings.partnerLanguage), settings.myLanguage)
            // Listen mode: everyone but me is a "partner" heard in Vietnamese; my own speech isn't translated.
            if (settings.listenMode && fromMe) { if (!u.ended) events.caption(true, u.original, "", false); continue }
            val label = speakerLabel(u.speaker)
            if (!u.ended) { events.caption(fromMe, label + u.original, label + u.translation, false); continue }
            finishSentence(fromMe, u.original, u.translation, label)
        }
    }

    private val speakerLabels = LinkedHashMap<String, String>()
    private fun speakerLabel(speaker: String): String {
        if (!settings.labelSpeakers || speaker.isEmpty()) return ""
        speakerLabels.getOrPut(speaker) { ('A' + speakerLabels.size.coerceAtMost(25)).toString() }
        return if (speakerLabels.size >= 2) speakerLabels[speaker] + ": " else ""
    }

    private fun finishSentence(fromMe: Boolean, original: String, draft: String, label: String = "") {
        scope.launch {
            val target = if (fromMe) settings.partnerLanguage else settings.myLanguage
            val translation = if (settings.refine && original.length >= 2) refine(original, draft, fromMe) ?: draft else draft
            if (translation.isBlank()) return@launch
            synchronized(history) { history.addLast(original to translation); while (history.size > 8) history.removeFirst() }
            events.caption(fromMe, label + original, label + translation, true)
            if (fromMe && settings.speakToPartner) app.speaker.speak(translation, queue = true, loudspeaker = true, language = target)
            if (!fromMe && settings.speakToMe) app.speaker.speak(translation, queue = true, loudspeaker = false, language = target)
        }
    }

    /** Gemini re-translation with the conversation context and glossary; null keeps the Soniox draft. */
    private suspend fun refine(original: String, draft: String, fromMe: Boolean): String? {
        val key = app.settings.apiKey(ProviderKind.GEMINI)
        if (key.isBlank()) return null
        val from = Languages.of(if (fromMe) settings.myLanguage else settings.partnerLanguage).english
        val to = Languages.of(if (fromMe) settings.partnerLanguage else settings.myLanguage).english
        val recent = synchronized(history) { history.toList() }
        val system = buildString {
            append("You are an expert live interpreter between ").append(Languages.of(settings.myLanguage).english).append(" and ")
                .append(Languages.of(settings.partnerLanguage).english).append(" in a face-to-face business conversation.\n")
            if (settings.contextText.isNotBlank()) append("Situation and domain: ").append(settings.contextText.take(2000)).append('\n')
            val (_, pairs) = SonioxWire.glossary(settings.glossary)
            if (pairs.isNotEmpty()) append("Mandatory glossary (source => target):\n").append(pairs.take(80).joinToString("\n") { "${it.first} => ${it.second}" }).append('\n')
            if (recent.isNotEmpty()) append("Recent conversation (original => translation):\n").append(recent.joinToString("\n") { "${it.first.take(200)} => ${it.second.take(200)}" }).append('\n')
            append("Translate the new sentence from ").append(from).append(" into ").append(to)
            append(". The speech-recognition text may contain errors; use the context to infer what was really meant. ")
            append("Use natural spoken ").append(to).append(" with the right forms of address and register for the relationship. ")
            append("Keep names, numbers, prices, units and dates exact. Reply with the translation only, no notes.")
        }
        val config = AiConfig(ProviderKind.GEMINI, model = settings.refineModel.ifBlank { "gemini-3.8-flash" },
            endpoint = ProviderKind.GEMINI.defaultEndpoint, systemPrompt = system, maxTokens = 400, timeoutSeconds = 12,
            historyTurns = 0, webSearch = false)
        return withTimeoutOrNull(6_000) {
            runCatching { app.ai.ask(config, key, emptyList(), "Sentence: $original\nDraft translation: $draft").text.trim() }
                .onFailure { DiagnosticLog.w("DICH", "dịch lại theo ngữ cảnh lỗi: ${it.message?.take(120)}") }.getOrNull()
        }?.takeIf { it.isNotBlank() && it.length < original.length * 6 + 40 }
    }

    override fun feed(pcm16k: ByteArray) {
        if (!running) return
        pending.write(pcm16k)
        while (pending.size() >= CHUNK) {
            val all = pending.toByteArray()
            val chunk = all.copyOfRange(0, CHUNK)
            pending.reset(); pending.write(all, CHUNK, all.size - CHUNK)
            if (ready) { socket?.send(chunk.toByteString()); lastSentAt = System.currentTimeMillis() }
        }
    }

    override fun gated(graceMs: Long): Boolean =
        app.speaker.speaking || System.currentTimeMillis() - app.speaker.lastEndedAt < graceMs

    override fun stop() {
        running = false
        socket?.let { runCatching { it.send("") }; it.close(1000, "bye") }
        socket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun friendly(reason: String) = when {
        reason.contains("401") || reason.contains("403") -> "Khóa Soniox không hợp lệ hoặc hết hạn"
        reason.contains("402") -> "Tài khoản Soniox hết tiền hoặc hết ngân sách"
        reason.contains("429") -> "Soniox giới hạn số kết nối; thử lại sau"
        else -> "Không kết nối được Soniox (${reason.take(80)})"
    }

    companion object { const val CHUNK = 3200 }
}
