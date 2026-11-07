package org.turboio.customai.translate

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.turboio.customai.TurboApp
import org.turboio.customai.ai.AiConfig
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.ambient.AudioSource
import org.turboio.customai.ambient.FrameSink
import org.turboio.customai.ambient.LifeLogSource
import org.turboio.customai.ambient.OpusBatch
import org.turboio.customai.ambient.PhoneMicSource
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.stt.DeepgramSpeechTranscriber
import org.turboio.customai.stt.EndPointer
import org.turboio.customai.stt.GeminiSpeechTranscriber
import org.turboio.customai.stt.GlassesOpusDecoder
import org.turboio.customai.stt.OpenAiTranscriber
import org.turboio.customai.stt.SttConfig
import org.turboio.customai.stt.SttEngine
import org.turboio.customai.tts.SpeechText
import java.io.ByteArrayOutputStream

/** Languages offered for the conversation: BCP-47 tag, name shown in the app, English name given to the model. */
object Languages {
    data class Language(val tag: String, val label: String, val english: String)
    val ALL = listOf(
        Language("vi-VN", "Tiếng Việt", "Vietnamese"),
        Language("en-US", "Tiếng Anh (English)", "English"),
        Language("zh-CN", "Tiếng Trung (中文)", "Mandarin Chinese (Simplified)"),
        Language("ja-JP", "Tiếng Nhật (日本語)", "Japanese"),
        Language("ko-KR", "Tiếng Hàn (한국어)", "Korean"),
        Language("th-TH", "Tiếng Thái (ไทย)", "Thai"),
        Language("fr-FR", "Tiếng Pháp (Français)", "French"),
        Language("de-DE", "Tiếng Đức (Deutsch)", "German"),
        Language("es-ES", "Tiếng Tây Ban Nha (Español)", "Spanish"),
        Language("ru-RU", "Tiếng Nga (Русский)", "Russian"),
        Language("id-ID", "Tiếng Indonesia", "Indonesian"),
        Language("pt-BR", "Tiếng Bồ Đào Nha (Português)", "Portuguese"),
    )
    fun of(tag: String): Language = ALL.firstOrNull { it.tag == tag } ?: ALL.first()
    /** Same language family ("vi" matches "vi-VN", "zh-TW" matches "zh-CN"). */
    fun same(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
}

enum class InterpreterSource(val id: String, val label: String) {
    GLASSES("glasses", "Mic của kính (khuyên dùng)"),
    PHONE("phone", "Mic của điện thoại");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GLASSES }
}

data class InterpreterSettings(
    val myLanguage: String = "vi-VN",
    val partnerLanguage: String = "en-US",
    val source: InterpreterSource = InterpreterSource.GLASSES,
    /** AI that hears/translates. Gemini hears the audio directly; other providers translate a transcript. */
    val provider: ProviderKind = ProviderKind.GEMINI,
    /** Empty = the model saved for [provider] in the AI tab. */
    val model: String = "",
    /** Translation of what I say, played on the phone's loudspeaker for the other person. */
    val speakToPartner: Boolean = true,
    /** Translation of what the other person says, read in my earphones. */
    val speakToMe: Boolean = true,
    val showOnLens: Boolean = true,
    /** Pause that ends a sentence. */
    val silenceMs: Int = 800,
)

data class InterpreterLine(val at: Long, val fromMe: Boolean, val heard: String, val translation: String, val language: String)

data class InterpreterState(
    val running: Boolean = false,
    val note: String = "Chưa bật phiên dịch",
    val lines: List<InterpreterLine> = emptyList(),
    val lastError: String = "",
)

/** Prompt and reply format (pure, unit-tested). */
object InterpreterPrompt {
    data class Result(val heard: String, val language: String, val translation: String)

    const val AUDIO_INSTRUCTION = "Interpret the utterance in the attached audio."

    fun system(my: String, partner: String, recent: List<InterpreterLine>): String {
        val me = Languages.of(my).english; val them = Languages.of(partner).english
        val context = if (recent.isEmpty()) "" else "\nRecent conversation, for context only (do not translate it again):\n" +
            recent.joinToString("\n") { "- " + it.heard.take(200) + " => " + it.translation.take(200) }
        return """You are a professional live interpreter between $me and $them in a face-to-face conversation.
Each request contains ONE utterance (as audio, or as its transcript).
1. "heard": write exactly what was said, in its original language and script, with correct diacritics and punctuation.
2. "language": the BCP-47 tag of the language spoken (for example vi-VN, en-US, zh-CN).
3. "translation": if the utterance is in $me, translate it into $them; if it is in any other language, translate it into $me.
Translate faithfully and naturally, the way a native speaker would say it aloud. Keep names, numbers, units and tone.
Never answer or comment on what was said, never add explanations or notes.$context
Reply with ONLY one JSON object, no Markdown: {"heard":"...","language":"...","translation":"..."}
If there is no intelligible speech (noise, coughing, music, silence), reply {"heard":""}."""
    }

    /** Tolerates Markdown fences or text around the JSON object; null when nothing usable came back. */
    fun parse(raw: String): Result? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val heard = o.optString("heard").trim()
        val translation = o.optString("translation").trim()
        if (heard.isEmpty() || translation.isEmpty()) return null
        return Result(heard, o.optString("language").trim(), translation)
    }

    /** Whether the utterance was mine (my language), from the model's tag or, failing that, the script. */
    fun fromMe(result: Result, my: String, partner: String): Boolean {
        if (result.language.isNotBlank()) return Languages.same(result.language, my)
        return Languages.same(SpeechText.guessLanguage(result.heard, partner), my)
    }
}

/** Cuts a continuous 16 kHz stream into utterances at pauses (pure, unit-tested). */
class UtteranceSegmenter(private val silenceMs: Int = 800, private val maxMs: Int = 12_000, private val preRollMs: Int = 400,
                         private val minMs: Int = 500) {
    private var pointer = EndPointer(silenceMs = silenceMs, maxMs = maxMs, noSpeechMs = 3_000)
    private val buf = ByteArrayOutputStream()
    private val durationMs get() = buf.size() / 32

    fun reset() { pointer = EndPointer(silenceMs = silenceMs, maxMs = maxMs, noSpeechMs = 3_000); buf.reset() }

    /** Returns a finished utterance (PCM16 LE) when this block completes one. */
    fun feed(samples: ShortArray): ByteArray? {
        for (v in samples) { buf.write(v.toInt() and 0xff); buf.write((v.toInt() shr 8) and 0xff) }
        val state = pointer.feed(samples)
        when (state) {
            EndPointer.State.WAITING -> if (durationMs > preRollMs) {     // keep only a little audio before speech
                val keep = preRollMs * 32
                val all = buf.toByteArray(); buf.reset(); buf.write(all, all.size - keep, keep)
            }
            EndPointer.State.DONE_SPEECH, EndPointer.State.DONE_MAX -> {
                val out = buf.toByteArray(); reset()
                return if (out.size / 32 >= minMs) out else null
            }
            EndPointer.State.DONE_NO_SPEECH -> reset()
            EndPointer.State.SPEAKING -> Unit
        }
        return null
    }
}

/**
 * Two-way interpreter. Listens continuously (glasses LifeLog microphones or the phone microphone), cuts the stream
 * into sentences, and has the chosen AI write what was heard and translate it: my language → partner's language
 * (shown on the lenses and played on the phone's loudspeaker for the other person), any other language → my
 * language (shown on the lenses and read in my earphones). The microphones are ignored while a translation is
 * being played, so the app does not translate its own voice.
 */
class Interpreter(private val app: TurboApp) : FrameSink {
    private val _state = MutableStateFlow(InterpreterState())
    val state: StateFlow<InterpreterState> = _state
    @Volatile private var source: AudioSource? = null
    @Volatile private var lifeLog: LifeLogSource? = null
    private val decoder = GlassesOpusDecoder()
    private var segmenter = UtteranceSegmenter()
    private var queue = newQueue()
    private var worker: Job? = null
    @Volatile private var settings = InterpreterSettings()

    val running: Boolean get() = source != null

    private fun newQueue() = Channel<ByteArray>(capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private fun update(t: (InterpreterState) -> InterpreterState) { _state.value = t(_state.value) }

    /** Returns null when started, otherwise the reason it could not start. */
    @Synchronized fun start(): String? {
        if (running) return null
        val s = app.settings.interpreterSettings().also { settings = it }
        if (!app.settings.hasApiKey(s.provider)) return "Chưa có khóa API cho ${s.provider.label} (tab AI)"
        if (s.provider != ProviderKind.GEMINI && app.settings.sttKey(SttEngine.GLASSES_OPENAI).isEmpty() && !app.settings.hasApiKey(ProviderKind.GEMINI))
            return "Với ${s.provider.label}, cần thêm khóa OpenAI hoặc Gemini để chép lời"
        val src: AudioSource = when (s.source) {
            InterpreterSource.GLASSES -> {
                if (!app.session.isReady) return "Hãy kết nối kính trước"
                if (app.probe.busy) return "Đang chạy bài kiểm tra phần cứng"
                if (app.ambient.usesGlasses) return "Kính đang ghi âm môi trường; hãy dừng ghi âm trước"
                LifeLogSource(app.session, app.scope, org.turboio.customai.probe.PrefsPendingFlag(app), app.random, realtime = true,
                    keepGuide = { app.settings.ambientSettings().glassesMenu },
                    onEnded = { reason -> app.scope.launch { stop("Kính đã dừng gửi âm thanh ($reason)") } }).also { lifeLog = it }
            }
            InterpreterSource.PHONE -> {
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    return "Hãy cho phép app dùng micro"
                PhoneMicSource()
            }
        }
        decoder.reset(); segmenter = UtteranceSegmenter(silenceMs = s.silenceMs)
        queue = newQueue()
        val q = queue
        worker = app.scope.launch(Dispatchers.IO) { for (pcm in q) process(pcm) }
        source = src
        try { src.start(this) } catch (e: Exception) {
            source = null; lifeLog = null; worker?.cancel(); q.close()
            return "Không mở được micro: ${e.message ?: e.javaClass.simpleName}"
        }
        val me = Languages.of(s.myLanguage).label; val them = Languages.of(s.partnerLanguage).label
        DiagnosticLog.i("DICH", "phiên dịch bật: ${s.myLanguage} <-> ${s.partnerLanguage}, nguồn ${s.source.id}, ${s.provider.id}")
        update { it.copy(running = true, note = "Đang nghe · $me ⇄ $them", lastError = "") }
        return null
    }

    @Synchronized fun stop(note: String = "Đã tắt phiên dịch") {
        val src = source ?: return
        source = null
        runCatching { src.stop() }
        queue.close(); worker = null
        update { it.copy(running = false, note = note) }
        DiagnosticLog.i("DICH", note)
    }

    /** LAUNCHER/AI_ASSISTANT traffic for the LifeLog microphones (also needed briefly after stop, until OFF is confirmed). */
    fun onGlasses(business: Int, envelope: Envelope) {
        lifeLog?.let { if (it.running || it.stopping) it.onBusiness(business, envelope) else lifeLog = null }
    }

    fun connectionChanged(ready: Boolean) { if (!ready && settings.source == InterpreterSource.GLASSES && running) stop("Mất kết nối kính; đã tắt phiên dịch") }

    fun clear() = update { it.copy(lines = emptyList()) }

    @Synchronized override fun frames(batch: OpusBatch) {
        if (!running) return
        // Ignore our own voice: while a translation plays, and briefly after (glasses audio arrives ~1 s late).
        val grace = if (settings.source == InterpreterSource.GLASSES) 1500 else 500
        if (app.speaker.speaking || System.currentTimeMillis() - app.speaker.lastEndedAt < grace || app.voice.busy) { segmenter.reset(); return }
        for (frame in batch.frames) {
            val samples = runCatching { decoder.decode(frame) }.getOrNull() ?: continue
            segmenter.feed(samples)?.let { queue.trySend(it) }
        }
    }

    @Synchronized override fun gap(reason: String) { segmenter.reset(); decoder.reset() }

    private suspend fun process(pcm: ByteArray) {
        val s = settings
        update { it.copy(note = "Đang dịch ${pcm.size / 32000.0} giây…") }
        val started = System.currentTimeMillis()
        val result = try { translate(s, pcm) } catch (e: Exception) {
            val reason = TurboApp.friendly(e)
            DiagnosticLog.w("DICH", "lỗi dịch: $reason")
            update { it.copy(note = "Lỗi: $reason", lastError = reason) }
            return
        }
        if (result == null) { update { it.copy(note = if (running) "Đang nghe…" else it.note) }; return }
        val fromMe = InterpreterPrompt.fromMe(result, s.myLanguage, s.partnerLanguage)
        val line = InterpreterLine(System.currentTimeMillis(), fromMe, result.heard, result.translation, result.language)
        DiagnosticLog.i("DICH", "câu ${if (fromMe) "của tôi" else "của đối phương"} dịch trong ${System.currentTimeMillis() - started} ms")
        update { it.copy(lines = (it.lines + line).takeLast(60), note = if (running) "Đang nghe…" else it.note) }
        if (s.showOnLens && app.session.isReady && !app.voice.busy) {
            val text = if (fromMe) "➜ " + result.translation else result.translation
            app.display.show(text, app.settings.displaySettings().copy(lingerSeconds = 7, pageSeconds = 4))
        }
        if (fromMe && s.speakToPartner) app.speaker.speak(result.translation, queue = true, loudspeaker = true, language = s.partnerLanguage)
        if (!fromMe && s.speakToMe) app.speaker.speak(result.translation, queue = true, loudspeaker = false, language = s.myLanguage)
    }

    private suspend fun translate(s: InterpreterSettings, pcm: ByteArray): InterpreterPrompt.Result? {
        val provider = s.provider
        val base = app.settings.aiConfig()
        val config = AiConfig(
            provider = provider,
            model = s.model.ifBlank { app.settings.modelFor(provider) },
            endpoint = app.settings.endpointFor(provider).ifEmpty { provider.defaultEndpoint },
            systemPrompt = InterpreterPrompt.system(s.myLanguage, s.partnerLanguage, _state.value.lines.takeLast(4)),
            maxTokens = 1000, timeoutSeconds = maxOf(base.timeoutSeconds, 30), historyTurns = 0, webSearch = false,
        )
        val key = app.settings.apiKey(provider)
        if (provider == ProviderKind.GEMINI) {
            val answer = app.ai.ask(config, key, emptyList(), InterpreterPrompt.AUDIO_INSTRUCTION, DeepgramSpeechTranscriber.wav(pcm))
            return InterpreterPrompt.parse(answer.text)
        }
        val heard = transcribe(s, pcm).trim()
        if (heard.isEmpty()) return null
        val answer = app.ai.ask(config, key, emptyList(), "Transcript of the utterance: \"$heard\"")
        return InterpreterPrompt.parse(answer.text)?.copy(heard = heard)
    }

    /** Any-language transcript for the non-Gemini path: OpenAI (automatic language) or Gemini. */
    private suspend fun transcribe(s: InterpreterSettings, pcm: ByteArray): String {
        val openAi = app.settings.sttKey(SttEngine.GLASSES_OPENAI)
        if (openAi.isNotEmpty()) {
            val model = app.settings.sttConfig().openAiModel.let { if (it == "whisper-1") "gpt-4o-transcribe" else it }
            return OpenAiTranscriber(SttConfig(language = "", openAiModel = model), openAi).transcribe(pcm)
        }
        val both = Languages.of(s.myLanguage).english + " or " + Languages.of(s.partnerLanguage).english
        return GeminiSpeechTranscriber(SttConfig(language = both), app.settings.apiKey(ProviderKind.GEMINI),
            app.settings.modelFor(ProviderKind.GEMINI)).transcribe(pcm)
    }
}
