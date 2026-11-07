package org.turboio.customai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.ai.readNBytesCompat
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Who reads the answer aloud. Cloud engines fall back to the phone's own voice when they fail. */
enum class TtsEngine(val id: String, val label: String) {
    OFF("off", "Tắt (không đọc)"),
    GEMINI("gemini", "Gemini TTS · giọng tự nhiên, đa ngôn ngữ (dùng khóa Gemini)"),
    OPENAI("openai", "OpenAI TTS · đa ngôn ngữ (dùng khóa OpenAI)"),
    SYSTEM("system", "Giọng của điện thoại (Android TTS, miễn phí, không cần mạng)");

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI }
}

data class TtsSettings(
    val engine: TtsEngine = TtsEngine.GEMINI,
    val geminiModel: String = "gemini-3.8-flash-lite-tts",
    val geminiVoice: String = "Kore",
    val openAiModel: String = "gpt-4o-mini-tts",
    val openAiVoice: String = "alloy",
    /** 0.5–2.0; 1.0 = normal speed. */
    val rate: Float = 1.0f,
    /** Language used when a sentence's language cannot be told from its letters (BCP-47, e.g. vi-VN). */
    val defaultLanguage: String = "vi-VN",
)

/** One piece of text to read, with the language guessed from its script. */
data class SpeechChunk(val text: String, val language: String)

/** Pure text helpers (unit-tested on the JVM). */
object SpeechText {
    private val VIETNAMESE = Regex("[ăâđêôơưàáảãạằắẳẵặầấẩẫậèẻẽẹềếểễệìỉĩịòỏõọồốổỗộờớởỡợùủũụừứửữựỳỷỹỵ]", RegexOption.IGNORE_CASE)
    private val HANGUL = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF]")
    private val KANA = Regex("[\\u3040-\\u30FF]")
    private val HAN = Regex("[\\u4E00-\\u9FFF]")
    private val CYRILLIC = Regex("[\\u0400-\\u04FF]")
    private val THAI = Regex("[\\u0E00-\\u0E7F]")
    private val LATIN = Regex("[A-Za-z]")
    private val SENTENCE_END = Regex("(?<=[.!?。！？;…])\\s+|\\n+")

    /** Best guess of the language of [text] from its letters; [fallback] when nothing distinctive is found. */
    fun guessLanguage(text: String, fallback: String): String = when {
        HANGUL.containsMatchIn(text) -> "ko-KR"
        KANA.containsMatchIn(text) -> "ja-JP"
        HAN.containsMatchIn(text) -> "zh-CN"
        CYRILLIC.containsMatchIn(text) -> "ru-RU"
        THAI.containsMatchIn(text) -> "th-TH"
        VIETNAMESE.containsMatchIn(text) -> "vi-VN"
        fallback.startsWith("vi") && LATIN.containsMatchIn(text) && looksEnglish(text) -> "en-US"
        else -> fallback
    }

    /** Latin text without Vietnamese marks is English only when it also has common English words (short Vietnamese words lose no marks). */
    private val ENGLISH_WORDS = Regex("\\b(the|is|are|and|you|of|to|in|it|this|that|for|with|what|how|hello|thank|yes|no|please)\\b", RegexOption.IGNORE_CASE)
    private fun looksEnglish(text: String) = ENGLISH_WORDS.containsMatchIn(text)

    /** Removes what should not be read aloud (Markdown marks, links, emoji) and collapses spaces. */
    fun forSpeech(raw: String): String {
        var t = raw.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("https?://\\S+"), "")
        t = t.replace(Regex("[*_#`>|~]+"), " ")
        t = t.replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }

    /**
     * Sentences grouped while they share a language and stay under [maxChars], so a mixed answer
     * ("Tiếng Anh gọi là 'apple'. Apple is a fruit.") switches voice at the right place.
     */
    fun chunks(raw: String, fallback: String, maxChars: Int = 280): List<SpeechChunk> {
        val text = forSpeech(raw)
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<SpeechChunk>()
        val buf = StringBuilder(); var lang = ""
        fun flush() { if (buf.isNotBlank()) out.add(SpeechChunk(buf.toString().trim(), lang)); buf.clear() }
        for (sentence in text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }) {
            for (piece in splitLong(sentence, maxChars)) {
                val l = guessLanguage(piece, fallback)
                if (buf.isNotEmpty() && (l != lang || buf.length + piece.length + 1 > maxChars)) flush()
                if (buf.isEmpty()) lang = l else buf.append(' ')
                buf.append(piece)
            }
        }
        flush()
        return out
    }

    private fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>(); var rest = s
        while (rest.length > max) {
            val cut = rest.lastIndexOfAny(charArrayOf(',', ' ', '，', '、'), max).takeIf { it > max / 3 } ?: max
            out.add(rest.substring(0, cut + 1).trim()); rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** 24 kHz mono PCM16 from either a RIFF/WAVE file or raw L16 bytes → (samples, sampleRate). */
    fun pcm(bytes: ByteArray, defaultRate: Int = 24000): Pair<ByteArray, Int> {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return bytes to defaultRate
        fun i32(at: Int) = (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() and 0xff shl 8) or (bytes[at + 2].toInt() and 0xff shl 16) or (bytes[at + 3].toInt() and 0xff shl 24)
        var pos = 12; var rate = defaultRate
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII); val size = i32(pos + 4)
            if (id == "fmt " && pos + 16 <= bytes.size) rate = i32(pos + 12)
            if (id == "data") {
                val start = pos + 8
                val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
                return bytes.copyOfRange(start, end) to rate
            }
            pos += 8 + size + (size and 1)
        }
        return bytes.copyOfRange(44, bytes.size) to rate
    }
}

class TtsException(message: String) : Exception(message)

/**
 * Reads answers aloud on the phone's current audio output: Bluetooth earphones when connected, otherwise the
 * speaker. Speech uses the assistant audio usage and a short ducking audio focus, so music lowers while it talks.
 * A new [speak] or [stop] interrupts whatever is being read.
 */
class Speaker(
    private val context: Context,
    private val settings: () -> TtsSettings,
    private val geminiKey: () -> String,
    private val openAiKey: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var lastNote = ""

    /** Last outcome, shown in the settings screen ("Đọc bằng Gemini…", errors). */
    val status: String get() = lastNote

    val speaking: Boolean get() = job?.isActive == true

    fun speak(text: String) {
        val s = settings()
        if (s.engine == TtsEngine.OFF || text.isBlank()) return
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val focus = requestFocus()
            try {
                val chunks = SpeechText.chunks(text, s.defaultLanguage)
                if (chunks.isEmpty()) return@launch
                val cloudOk = when (s.engine) {
                    TtsEngine.GEMINI, TtsEngine.OPENAI -> runCatching { speakCloud(chunks, s) }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastNote = "Lỗi giọng cloud: ${e.message}; chuyển sang giọng điện thoại"
                        DiagnosticLog.w("TTS", lastNote)
                    }.isSuccess
                    else -> false
                }
                if (!cloudOk) speakSystem(chunks, s)
            } finally {
                stopTrack()
                abandonFocus(focus)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        stopTrack()
        runCatching { system?.stop() }
    }

    // ---------------------------------------------------------------- cloud voices

    private suspend fun speakCloud(chunks: List<SpeechChunk>, s: TtsSettings) {
        val started = System.currentTimeMillis()
        // Fetch the next piece while the current one plays, so long answers have no gaps.
        var next: Deferred<Pair<ByteArray, Int>>? = scope.async { synthesize(chunks[0], s) }
        try {
            for (i in chunks.indices) {
                val current = next!!.await()
                next = if (i + 1 < chunks.size) scope.async { synthesize(chunks[i + 1], s) } else null
                if (i == 0) DiagnosticLog.i("TTS", "${s.engine.id}: primeiro áudio em ${System.currentTimeMillis() - started} ms (${chunks.size} trechos)")
                play(current.first, current.second)
            }
        } finally { next?.cancel() }
        lastNote = "Đã đọc bằng ${s.engine.label.substringBefore(" ·")}"
    }

    private suspend fun synthesize(chunk: SpeechChunk, s: TtsSettings): Pair<ByteArray, Int> = when (s.engine) {
        TtsEngine.OPENAI -> openAi(chunk.text, s)
        else -> gemini(chunk.text, s)
    }

    private suspend fun gemini(text: String, s: TtsSettings): Pair<ByteArray, Int> {
        val key = geminiKey()
        if (key.isBlank()) throw TtsException("chưa có khóa Gemini (tab IA)")
        if (!Regex("^[A-Za-z0-9._-]{1,80}$").matches(s.geminiModel)) throw TtsException("tên model TTS không hợp lệ")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", s.geminiVoice)))).toString()
        val (status, response) = post("https://generativelanguage.googleapis.com/v1beta/models/${s.geminiModel}:generateContent",
            mapOf("x-goog-api-key" to key, "Content-Type" to "application/json; charset=utf-8"), body.toByteArray())
        if (status !in 200..299) throw TtsException("Gemini HTTP $status ${errorDetail(response)}")
        val parts = JSONObject(String(response)).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw TtsException("Gemini không trả về âm thanh")
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val data = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data").orEmpty()
            if (data.isNotEmpty()) return SpeechText.pcm(Base64.getDecoder().decode(data))
        }
        throw TtsException("Gemini không trả về âm thanh")
    }

    private suspend fun openAi(text: String, s: TtsSettings): Pair<ByteArray, Int> {
        val key = openAiKey()
        if (key.isBlank()) throw TtsException("chưa có khóa OpenAI (tab IA)")
        val body = JSONObject().put("model", s.openAiModel).put("voice", s.openAiVoice).put("input", text)
            .put("response_format", "pcm").put("speed", s.rate.coerceIn(0.5f, 2.0f).toDouble()).toString()
        val (status, response) = post("https://api.openai.com/v1/audio/speech",
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"), body.toByteArray())
        if (status !in 200..299) throw TtsException("OpenAI HTTP $status ${errorDetail(response)}")
        return response to 24000
    }

    private fun errorDetail(body: ByteArray): String = runCatching {
        Redactor.redact(JSONObject(String(body)).optJSONObject("error")?.optString("message").orEmpty()).take(120)
    }.getOrDefault("")

    private suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): Pair<Int, ByteArray> =
        runInterruptible(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
                c.instanceFollowRedirects = false
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                code to ((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(16 shl 20) } ?: ByteArray(0))
            } finally { c.disconnect() }
        }

    /** Plays mono PCM16 and returns when it has been heard (or when cancelled). */
    private suspend fun play(pcm: ByteArray, rate: Int) {
        if (pcm.isEmpty()) return
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var off = 0
            while (off < pcm.size) {
                coroutineContext.ensureActive()
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            val frames = pcm.size / 2
            // stop() in stream mode lets the buffered audio finish; wait until the head reaches the end.
            t.stop()
            val deadline = System.currentTimeMillis() + frames * 1000L / rate + 1500
            while (coroutineContext.isActive && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) delay(40)
        } finally {
            if (track === t) track = null
            runCatching { t.pause(); t.flush() }
            runCatching { t.release() }
        }
    }

    private fun stopTrack() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    // ---------------------------------------------------------------- phone voice (Android TextToSpeech)

    @Volatile private var system: TextToSpeech? = null
    @Volatile private var systemOk = false
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun systemTts(): TextToSpeech? {
        system?.let { if (systemOk) return it }
        val ready = CompletableDeferred<Boolean>()
        val engine = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = withTimeoutOrNull(8000) { ready.await() } ?: false
        if (!ok) {
            runCatching { engine.shutdown() }
            lastNote = "Điện thoại không có bộ đọc văn bản (TTS). Hãy cài một ứng dụng TTS hoặc dùng Gemini TTS."
            DiagnosticLog.w("TTS", lastNote)
            return null
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
        })
        system = engine; systemOk = true
        return engine
    }

    private suspend fun speakSystem(chunks: List<SpeechChunk>, s: TtsSettings) {
        val tts = systemTts() ?: return
        tts.setSpeechRate(s.rate.coerceIn(0.5f, 2.0f))
        val missing = mutableSetOf<String>()
        for (chunk in chunks) {
            coroutineContext.ensureActive()
            val wanted = Locale.forLanguageTag(chunk.language)
            if (tts.setLanguage(wanted) < TextToSpeech.LANG_AVAILABLE) {
                missing.add(chunk.language)
                tts.setLanguage(Locale.forLanguageTag(s.defaultLanguage))
            }
            val id = "turboio-" + System.nanoTime()
            val done = CompletableDeferred<Unit>().also { pending[id] = it }
            tts.speak(chunk.text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            try {
                withTimeoutOrNull(chunk.text.length * 200L + 10_000) { done.await() }
            } catch (e: kotlinx.coroutines.CancellationException) { tts.stop(); throw e }
        }
        lastNote = if (missing.isEmpty()) "Đã đọc bằng giọng điện thoại"
            else "Điện thoại thiếu giọng: ${missing.joinToString()} (cài thêm trong Cài đặt → Chuyển văn bản thành lời nói)"
        if (missing.isNotEmpty()) DiagnosticLog.w("TTS", lastNote)
    }

    // ---------------------------------------------------------------- audio focus

    private fun requestFocus(): AudioFocusRequest? = runCatching {
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        audio.requestAudioFocus(r); r
    }.getOrNull()

    private fun abandonFocus(r: AudioFocusRequest?) { if (r != null) runCatching { audio.abandonAudioFocusRequest(r) } }
}
