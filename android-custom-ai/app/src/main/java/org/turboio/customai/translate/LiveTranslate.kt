package org.turboio.customai.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemini Live Translate wire format (pure, unit-tested). One session translates everything it hears into ONE target
 * language; with `echoTargetLanguage:false` it stays silent for speech already in that language, so two sessions on
 * the same audio make a two-way interpreter.
 */
object LiveWire {
    const val DEFAULT_MODEL = "gemini-3.5-live-translate-preview"
    fun endpoint(version: String) =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$version.GenerativeService.BidiGenerateContent"

    /** Live Translate takes plain language codes ("vi", "en", "zh"). */
    fun languageCode(tag: String): String = tag.substringBefore('-').lowercase()

    fun setup(model: String, targetTag: String): String = JSONObject().put("setup", JSONObject()
        .put("model", "models/$model")
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("translationConfig", JSONObject().put("targetLanguageCode", languageCode(targetTag)).put("echoTargetLanguage", false)))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject())).toString()

    fun audio(pcm16k: ByteArray): String = JSONObject().put("realtimeInput", JSONObject()
        .put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(pcm16k)).put("mimeType", "audio/pcm;rate=16000"))).toString()

    sealed class Event {
        object SetupComplete : Event()
        data class Input(val text: String) : Event()
        data class Output(val text: String) : Event()
        class Audio(val pcm24k: ByteArray) : Event()
        object TurnComplete : Event()
        object Interrupted : Event()
        data class GoAway(val timeLeft: String) : Event()
        data class Error(val message: String) : Event()
    }

    /** All events in one server message, in a stable order (transcripts before audio, completion last). */
    fun parse(raw: String): List<Event> {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Event>()
        if (o.has("setupComplete")) out.add(Event.SetupComplete)
        o.optJSONObject("error")?.let { out.add(Event.Error(it.optString("message", it.toString()))) }
        o.optJSONObject("goAway")?.let { out.add(Event.GoAway(it.optString("timeLeft"))) }
        val sc = o.optJSONObject("serverContent") ?: return out
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(Event.Input(it)) }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(Event.Output(it)) }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val data = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data").orEmpty()
            if (data.isNotEmpty()) runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { out.add(Event.Audio(it)) }
        }
        if (sc.optBoolean("interrupted")) out.add(Event.Interrupted)
        if (sc.optBoolean("turnComplete")) out.add(Event.TurnComplete)
        return out
    }

    /** The newest part of a running caption that fits the lens, cut at a word boundary. */
    fun tail(text: String, max: Int = 80): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.substring(t.length - max)
        val space = cut.indexOf(' ')
        return "…" + (if (space in 1..20) cut.substring(space + 1) else cut)
    }
}

/** One Live Translate WebSocket. Callbacks arrive on OkHttp's thread. */
class LiveTranslateSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val targetTag: String,
    private val name: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(session: LiveTranslateSession)
        fun onEvent(session: LiveTranslateSession, event: LiveWire.Event)
        /** [beforeReady]: the setup was refused (wrong model, version, key, quota…). */
        fun onClosed(session: LiveTranslateSession, reason: String, beforeReady: Boolean)
    }

    @Volatile var ready = false; private set
    @Volatile private var closed = false
    private var socket: WebSocket? = null
    val target: String get() = targetTag

    fun connect(version: String) {
        val request = Request.Builder().url(LiveWire.endpoint(version) + "?key=" + apiKey).build()
        DiagnosticLog.i("DICH", "$name: kết nối Live Translate ($version, $model, đích ${LiveWire.languageCode(targetTag)})")
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(LiveWire.setup(model, targetTag)) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); finish("đóng ($code) ${Redactor.redact(reason).take(160)}")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { r -> "HTTP ${r.code} " + runCatching { r.body?.string().orEmpty() }.getOrDefault("").take(160) }
                    ?: (t.message ?: t.javaClass.simpleName)
                finish("lỗi kết nối: ${Redactor.redact(detail)}")
            }
        })
    }

    private fun handle(raw: String) {
        if (closed) return
        for (e in LiveWire.parse(raw)) {
            if (e is LiveWire.Event.SetupComplete) { ready = true; DiagnosticLog.i("DICH", "$name: sẵn sàng"); listener.onReady(this); continue }
            listener.onEvent(this, e)
        }
    }

    private fun finish(reason: String) {
        if (closed) return
        closed = true
        DiagnosticLog.w("DICH", "$name: $reason")
        listener.onClosed(this, reason, !ready)
        ready = false
    }

    fun sendAudio(json: String): Boolean = ready && !closed && socket?.send(json) == true

    fun close() {
        if (closed) return
        closed = true; ready = false
        socket?.close(1000, "bye")
    }
}

/** Streams 24 kHz mono PCM16 to one output; the phone's loudspeaker when [loudspeaker], else the current route. */
class PcmPlayer(context: Context, private val loudspeaker: Boolean, private val name: String) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    @Volatile private var track: AudioTrack? = null
    /** Wall-clock time until which written audio is still playing (for the echo guard). */
    @Volatile var busyUntil = 0L; private set

    private val thread = Thread({
        try {
            while (running) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val t = track ?: open().also { track = it }
                val now = System.currentTimeMillis()
                busyUntil = maxOf(busyUntil, now) + chunk.size * 1000L / (RATE * 2)
                var off = 0
                while (off < chunk.size && running) { val n = t.write(chunk, off, chunk.size - off); if (n <= 0) break; off += n }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { DiagnosticLog.w("DICH", "$name: lỗi phát âm thanh ${e.javaClass.simpleName}") }
        finally { track?.let { runCatching { it.pause(); it.flush(); it.release() } }; track = null }
    }, "turboio-pcm-$name").apply { isDaemon = true; start() }

    private fun open(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (loudspeaker) audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { t.setPreferredDevice(it) }
        t.play()
        return t
    }

    fun play(pcm: ByteArray) { if (running && pcm.isNotEmpty()) queue.offer(pcm) }

    /** Drops what is queued (the model was interrupted). */
    fun flush() { queue.clear(); track?.let { runCatching { it.pause(); it.flush(); it.play() } }; busyUntil = System.currentTimeMillis() }

    fun release() { running = false; queue.clear(); thread.interrupt() }

    companion object { const val RATE = 24_000 }
}

/**
 * Two-way live interpreter on Gemini Live Translate, modelled on live captions: the microphone audio streams
 * continuously to two sessions (→ my language, → partner's language). Each one speaks only when the speech is NOT
 * already in its language, so the direction follows whoever is talking. Translated speech plays as it is produced
 * (mine to the loudspeaker for the other person, theirs to my earphones) and the text updates on the lenses while
 * the sentence is still being spoken.
 */
class LiveInterpreter(
    private val context: Context,
    private val settings: InterpreterSettings,
    private val apiKey: String,
    private val events: Events,
) {
    interface Events {
        /** A caption while the sentence is still forming ([final] false) and once it is complete. */
        fun caption(fromMe: Boolean, heard: String, translation: String, final: Boolean)
        fun note(text: String)
        fun failed(reason: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val model = settings.liveModel.ifBlank { LiveWire.DEFAULT_MODEL }
    private var version = "v1beta"
    @Volatile private var running = false
    private var failures = 0
    private val ear = PcmPlayer(context, loudspeaker = false, name = "tai-nghe")
    private val speaker = PcmPlayer(context, loudspeaker = true, name = "loa")
    private val sessions = HashMap<Boolean, LiveTranslateSession>()   // key: fromMe (true = translating MY speech)
    private val heard = HashMap<Boolean, StringBuilder>()
    private val said = HashMap<Boolean, StringBuilder>()
    private val pending = java.io.ByteArrayOutputStream()

    /** True while translated speech plays (and shortly after): the microphones then send silence, never our own voice. */
    fun gated(graceMs: Long): Boolean = System.currentTimeMillis() < maxOf(ear.busyUntil, speaker.busyUntil) + graceMs

    fun start() {
        running = true
        open(true); open(false)
        events.note("Đang kết nối Gemini Live Translate…")
    }

    private fun open(fromMe: Boolean) {
        val target = if (fromMe) settings.partnerLanguage else settings.myLanguage
        val name = if (fromMe) "phiên dịch lời tôi" else "phiên dịch lời đối phương"
        heard[fromMe] = StringBuilder(); said[fromMe] = StringBuilder()
        val s = LiveTranslateSession(client, apiKey, model, target, name, object : LiveTranslateSession.Listener {
            override fun onReady(session: LiveTranslateSession) {
                failures = 0
                if (sessions.values.all { it.ready }) events.note("Đang nghe · nói chuyện bình thường")
            }
            override fun onEvent(session: LiveTranslateSession, event: LiveWire.Event) = handle(fromMe, event)
            override fun onClosed(session: LiveTranslateSession, reason: String, beforeReady: Boolean) {
                if (!running || sessions[fromMe] !== session) return
                if (beforeReady && version == "v1beta") {          // translationConfig may still be alpha-only
                    version = "v1alpha"; DiagnosticLog.i("DICH", "thử lại bằng v1alpha"); reopen(fromMe, 300); return
                }
                failures++
                if (beforeReady && failures >= 3 || failures >= 8) {
                    running = false; events.failed(friendly(reason)); return
                }
                events.note("Mất kết nối dịch; đang kết nối lại…")
                reopen(fromMe, 800L * failures)
            }
        })
        synchronized(sessions) { sessions[fromMe] = s }
        s.connect(version)
    }

    private fun reopen(fromMe: Boolean, delayMs: Long) {
        Thread { Thread.sleep(delayMs); if (running) open(fromMe) }.start()
    }

    private fun handle(fromMe: Boolean, e: LiveWire.Event) {
        val h = heard.getValue(fromMe); val o = said.getValue(fromMe)
        when (e) {
            is LiveWire.Event.Input -> h.append(e.text)
            is LiveWire.Event.Output -> { o.append(e.text); events.caption(fromMe, h.toString().trim(), o.toString().trim(), false) }
            is LiveWire.Event.Audio -> {
                if (fromMe && settings.speakToPartner) speaker.play(e.pcm24k)
                if (!fromMe && settings.speakToMe) ear.play(e.pcm24k)
            }
            is LiveWire.Event.Interrupted -> (if (fromMe) speaker else ear).flush()
            is LiveWire.Event.TurnComplete -> {
                val translation = o.toString().trim()
                // echoTargetLanguage=false: a turn in the session's own language produces no translation; it is not shown.
                if (translation.isNotEmpty()) events.caption(fromMe, h.toString().trim(), translation, true)
                h.setLength(0); o.setLength(0)
            }
            is LiveWire.Event.GoAway -> { DiagnosticLog.i("DICH", "máy chủ sắp đóng phiên (${e.timeLeft}); sẽ tự kết nối lại") }
            is LiveWire.Event.Error -> DiagnosticLog.w("DICH", "lỗi từ máy chủ: ${Redactor.redact(e.message).take(160)}")
            LiveWire.Event.SetupComplete -> Unit
        }
    }

    /** 16 kHz mono PCM16 from the microphone; sent in 100 ms pieces to both sessions. */
    fun feed(pcm16k: ByteArray) {
        if (!running) return
        pending.write(pcm16k)
        while (pending.size() >= CHUNK) {
            val all = pending.toByteArray()
            val json = LiveWire.audio(all.copyOfRange(0, CHUNK))
            pending.reset(); pending.write(all, CHUNK, all.size - CHUNK)
            synchronized(sessions) { sessions.values.forEach { it.sendAudio(json) } }
        }
    }

    fun stop() {
        running = false
        synchronized(sessions) { sessions.values.forEach { it.close() }; sessions.clear() }
        ear.release(); speaker.release()
        client.dispatcher.executorService.shutdown()
    }

    private fun friendly(reason: String): String = when {
        reason.contains("429") || reason.contains("quota", true) || reason.contains("RESOURCE_EXHAUSTED") ->
            "Hết hạn mức Gemini cho dịch trực tiếp. Hãy bật thanh toán trong AI Studio hoặc dùng chế độ “Từng câu”."
        reason.contains("not found", true) || reason.contains("404") || reason.contains("not supported", true) ->
            "Khóa này chưa dùng được model $model. Hãy thử chế độ “Từng câu” hoặc đổi model trong cài đặt."
        reason.contains("API key", true) || reason.contains("403") || reason.contains("401") -> "Khóa Gemini không hợp lệ hoặc không có quyền."
        else -> "Không kết nối được Gemini Live Translate ($reason)"
    }

    companion object { const val CHUNK = 3200 }   // 100 ms at 16 kHz mono PCM16
}
