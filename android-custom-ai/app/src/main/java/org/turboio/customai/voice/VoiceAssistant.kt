package org.turboio.customai.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.DisplayText
import org.turboio.customai.display.newSid
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Commands
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.stt.EndPointer
import org.turboio.customai.stt.GlassesOpusDecoder
import org.turboio.customai.stt.PcmBuffer

/** Audio source for one turn. Glasses mic = AI_ASSISTANT type-3 Opus stream; phone mic = Android recognizer. */
interface SpeechInput {
    val usesGlassesMic: Boolean
    /** Glasses mode: transcribe captured PCM. */
    suspend fun transcribe(pcm: ByteArray): String = throw UnsupportedOperationException()
    /** Glasses mode, direct: the model hears the audio and answers in one request → (question heard, answer); null = not available. */
    suspend fun answerAudio(pcm: ByteArray): Pair<String, String>? = null
    val direct: Boolean get() = false
    /** Phone mode: listen on the phone microphone; returns transcript or throws with a pt-BR message. */
    suspend fun listenOnPhone(onPartial: (String) -> Unit): String = throw UnsupportedOperationException()
    fun cancel() {}
}

data class VoiceSettings(
    val enabled: Boolean = true,
    val stripAccents: Boolean = false,
    val silenceMs: Int = 1200,
    val maxSeconds: Int = 15,
    val displaySeconds: Int = 12,
    /** Listen again when the glasses offer another round (13/11). Off by default: the official app disables it on the iO. */
    val followUp: Boolean = false,
)

data class VoiceState(
    val phase: String = "inativo",
    val note: String = "Trợ lý giọng nói đang chờ kết nối",
    val transcript: String = "",
    val answer: String = "",
    val audioPackets: Int = 0,
    val audioMs: Long = 0,
    val rounds: Int = 0,
    val lastError: String = "",
)

/**
 * Voice turn on the ORIGINAL firmware's AI_ASSISTANT(13) channel, following the hardware-validated
 * HarmonyOS VoiceSession: glasses wake (type 1) → recorder start (type 2 rc:1) → Opus audio (type 3) →
 * transcript (type 5) → recorder stop → answer chunks ≤24 chars (type 32) → EOF (type 12) →
 * after the display period: recorder stop + exit (type 7). Type 8 = user closed on glasses,
 * type 11 = firmware offers a follow-up turn.
 */
class VoiceAssistant(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val random: Randomness,
    private val input: () -> SpeechInput,
    private val answer: suspend (String) -> String,
    private val settings: () -> VoiceSettings,
    private val canBegin: () -> Boolean = { true },
    /** Reads the answer aloud (earphones); [stopSpeaking] interrupts it when a new turn starts or the user closes. */
    private val speak: (String) -> Unit = {},
    private val stopSpeaking: () -> Unit = {},
) : BusinessListener {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private var epoch = 0L
    private var job: Job? = null
    private var displayDeadline: Job? = null
    private var sid = ""
    private var capturing = false
    private var followupEligible = false
    /** Answer already produced together with the transcript (direct Gemini mode). */
    private var directReply: String? = null
    private var speech: SpeechInput? = null
    private val decoder = GlassesOpusDecoder()
    private val pcm = PcmBuffer(40)
    private var endPointer = EndPointer()
    private var captured: CompletableDeferred<EndPointer.State>? = null
    private var firstAudio: CompletableDeferred<Unit>? = null

    init { port.addBusinessListener(this) }

    val busy: Boolean get() = job?.isActive == true || displayDeadline?.isActive == true

    private fun update(transform: (VoiceState) -> VoiceState) { _state.value = transform(_state.value) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.AI_ASSISTANT) return
        scope.launch { handle(envelope) }
    }

    private fun handle(envelope: Envelope) {
        when (envelope.type) {
            1 -> {
                if (!settings().enabled) { DiagnosticLog.i("VOZ", "despertar ignorado: assistente desativado"); return }
                if (!canBegin()) { DiagnosticLog.i("VOZ", "despertar ignorado: exibição de teste em andamento"); return }
                DiagnosticLog.i("VOZ", "despertar recebido dos óculos")
                begin(followup = false)
            }
            11 -> if (!settings().followUp) DiagnosticLog.d("VOZ", "nova rodada oferecida pelos óculos; ignorada (como no app oficial do iO)")
            else if (followupEligible && !capturing && displayDeadline?.isActive == true) {
                DiagnosticLog.i("VOZ", "óculos ofereceram nova rodada")
                begin(followup = true)
            }
            8 -> { DiagnosticLog.i("VOZ", "usuário encerrou nos óculos"); stopSpeaking(); endRound("Encerrado nos óculos", sendExit = false) }
            3 -> audio(envelope.binary)
        }
    }

    private fun audio(packet: ByteArray) {
        if (!capturing) return
        val samples = try { decoder.decode(packet) } catch (e: Exception) {
            DiagnosticLog.w("VOZ", "pacote de áudio inválido (${e.message})"); return
        }
        firstAudio?.complete(Unit)
        pcm.append(samples)
        val st = endPointer.feed(samples)
        update { it.copy(audioPackets = it.audioPackets + 1, audioMs = pcm.durationMs) }
        if (endPointer.finished) captured?.complete(st)
    }

    /** Starts a turn. A follow-up keeps the previous answer on screen until real speech is recognised. */
    private fun begin(followup: Boolean) {
        val token = ++epoch
        job?.cancel()
        speech?.cancel()
        stopSpeaking()
        if (!followup) { displayDeadline?.cancel(); displayDeadline = null }
        followupEligible = false
        sid = newSid(random)
        val source = input().also { speech = it }
        update { it.copy(phase = "ouvindo", note = if (source.usesGlassesMic) "Đang nghe bằng micro của kính…" else "Đang nghe bằng micro điện thoại…",
            transcript = if (followup) it.transcript else "", answer = if (followup) it.answer else "", audioPackets = 0, audioMs = 0, lastError = "") }
        job = scope.launch {
            val text = try {
                if (source.usesGlassesMic) captureFromGlasses(token, source) else captureFromPhone(token, source)
            } catch (e: Exception) {
                if (token == epoch) {
                    // No speech in the extra round: end it (the previous display deadline belonged to the old round).
                    if (followup) endRound("Xong lượt (lượt mới không có lời nói)", sendExit = true)
                    else endRound(e.message ?: "Nhận dạng thất bại", sendExit = true, error = true)
                }
                return@launch
            }
            if (token != epoch) return@launch
            if (followup) { displayDeadline?.cancel(); displayDeadline = null }
            respond(token, text)
        }
    }

    private suspend fun captureFromGlasses(token: Long, source: SpeechInput): String {
        val s = settings()
        decoder.reset(); pcm.clear()
        endPointer = EndPointer(silenceMs = s.silenceMs, maxMs = s.maxSeconds * 1000)
        val done = CompletableDeferred<EndPointer.State>().also { captured = it }
        val first = CompletableDeferred<Unit>().also { firstAudio = it }
        capturing = true
        send(2, Commands.recorder(true))?.let { capturing = false; throw IllegalStateException("Chưa gửi được lệnh bắt đầu ghi: $it") }
        if (withTimeoutOrNull(6000) { first.await() } == null) { capturing = false; throw IllegalStateException("Không nhận được âm thanh từ kính trong 6 giây") }
        val result = withTimeoutOrNull((s.maxSeconds + 5) * 1000L) { done.await() } ?: EndPointer.State.DONE_MAX
        capturing = false
        if (token != epoch) throw IllegalStateException("turno substituído")
        send(2, Commands.recorder(false))
        // Quiet microphones: still try STT when something audible was captured (threshold needs hardware calibration).
        if (result == EndPointer.State.DONE_NO_SPEECH && (endPointer.peakRms < 120 || pcm.durationMs < 800)) {
            throw IllegalStateException("Không nghe thấy lời nói nào")
        }
        update { it.copy(phase = "transcrevendo", note = "Đang chép lời ${pcm.durationMs / 1000.0} giây âm thanh…") }
        if (source.direct) send(9, Commands.SPEECH_THINKING)
        source.answerAudio(pcm.bytes())?.let { (heard, reply) ->
            pcm.clear()
            if (heard.isBlank()) throw IllegalStateException("Không nghe rõ. Hãy thử lại.")
            directReply = reply
            return heard.trim()
        }
        val text = source.transcribe(pcm.bytes()).trim()
        pcm.clear()
        if (text.isEmpty()) throw IllegalStateException("Không nghe rõ. Hãy thử lại.")
        return text
    }

    private suspend fun captureFromPhone(token: Long, source: SpeechInput): String {
        var last = 0L
        var finished = false
        val text = try { source.listenOnPhone { partial ->
            scope.launch {
                // A late partial must never overwrite the final transcript/answer on the lens.
                if (token != epoch || finished) return@launch
                update { it.copy(transcript = partial) }
                val now = System.currentTimeMillis()
                if (now - last >= 450) { last = now; send(5, Commands.transcript(Commands.chunks(partial, 100)[0], false)) }
            }
        }.trim() } finally { finished = true }
        if (text.isEmpty()) throw IllegalStateException("Không nghe rõ. Hãy thử lại.")
        return text
    }

    private suspend fun respond(token: Long, question: String) {
        update { it.copy(phase = "processando", note = "Đang hỏi AI…", transcript = question) }
        DiagnosticLog.i("VOZ", "pergunta reconhecida (${question.length} caracteres)")
        send(5, Commands.transcript(Commands.chunks(question, 100)[0], true))
        send(2, Commands.recorder(false))
        if (CLOSE.matches(question.trim().lowercase())) { endRound("Đã nhận lệnh kết thúc", sendExit = true); return }
        followupEligible = true
        val preset = directReply.also { directReply = null }
        if (preset == null) send(9, Commands.SPEECH_THINKING)   // "thinking" on the glasses while the model answers
        val reply = preset ?: try { answer(question) } catch (e: Exception) {
            if (token == epoch) {
                val reason = e.message ?: "AI gặp lỗi"
                sendAnswer(token, "Lỗi: ${reason.take(80)}", question)
                endRound(reason, sendExit = true, error = true, afterMs = 6000)
            }
            return
        }
        if (token != epoch) return
        val s = settings()
        val clean = DisplayText.clean(reply, s.stripAccents)
        val clipped = if (clean.length > 600) clean.take(560).trimEnd() + "… (xem đầy đủ trên điện thoại)" else clean
        update { it.copy(answer = clean, note = "Đang hiển thị câu trả lời trên kính") }
        if (!sendAnswer(token, clipped, question)) return
        speak(clean)
        update { it.copy(phase = "exibindo", rounds = it.rounds + 1, note = "Đã gửi câu trả lời; kết thúc sau ${displaySeconds(s, clipped)} giây") }
        val linger = displaySeconds(s, clipped) * 1000L
        displayDeadline = scope.launch { delay(linger); if (token == epoch) endRound("Xong lượt", sendExit = true) }
        job = null
    }

    private fun displaySeconds(s: VoiceSettings, text: String) = maxOf(s.displaySeconds, minOf(45, text.length / 12))

    private suspend fun sendAnswer(token: Long, text: String, question: String): Boolean {
        val query = Commands.chunks(question, 16)[0]
        send(9, Commands.SPEECH_RESPONDING)
        for (chunk in Commands.chunks(text, 24)) {
            if (token != epoch) return false
            send(32, Commands.answerChunk(chunk, sid, query, System.currentTimeMillis()))?.let {
                endRound("Chưa gửi được câu trả lời lên kính: $it", sendExit = false, error = true); return false
            }
        }
        if (token != epoch) return false
        send(12, Commands.EOF)
        return true
    }

    private suspend fun send(type: Int, json: String): String? {
        if (!port.isReady) return "Chưa kết nối kính"
        return port.sendEnvelope(Business.AI_ASSISTANT, type, json)
    }

    private fun endRound(note: String, sendExit: Boolean, error: Boolean = false, afterMs: Long = 0) {
        val token = ++epoch
        job?.cancel(); job = null
        displayDeadline?.cancel(); displayDeadline = null
        speech?.cancel(); speech = null
        capturing = false; followupEligible = false
        captured = null; firstAudio = null; pcm.clear()
        if (error) DiagnosticLog.w("VOZ", note) else DiagnosticLog.i("VOZ", note)
        update { it.copy(phase = "đang chờ", note = note, lastError = if (error) note else it.lastError) }
        if (sendExit && port.isReady) scope.launch {
            if (afterMs > 0) delay(afterMs)
            if (token != epoch) return@launch
            send(2, Commands.recorder(false)); send(7, Commands.EXIT)
        }
    }

    /** Called when the connection changes. */
    fun connectionChanged(ready: Boolean) {
        if (!ready && busy) endRound("Mất kết nối; lượt bị ngắt", sendExit = false, error = true)
        update { if (ready) it.copy(phase = "đang chờ", note = "Chạm/gọi trợ lý trên kính để nói") else it.copy(phase = "inativo", note = "Đang chờ kết nối với kính") }
    }

    companion object {
        private val CLOSE = Regex("^(encerrar|encerra|fechar|fecha|sair|cancelar|cancela|tchau|pode fechar|obrigad[oa],? (pode )?fechar|" +
            "thôi|thôi nhé|thôi được rồi|tắt|tắt đi|đóng|đóng lại|kết thúc|dừng|dừng lại|hủy|huỷ|tạm biệt|cảm ơn,? (tắt|đóng) đi|stop|cancel|bye)[.!\\s]*$")
    }
}
