package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import org.turboio.customai.TurboApp
import org.turboio.customai.ai.AiConfig
import org.turboio.customai.ai.DEFAULT_SYSTEM_PROMPT
import org.turboio.customai.ai.ProviderCodec
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import org.turboio.customai.stt.SttEngine
import org.turboio.customai.tts.TtsEngine

class AiFragment : Fragment() {
    private val settings get() = TurboApp.instance.settings
    private var provider = ProviderKind.OPENAI
    private var engine = SttEngine.PHONE_ANDROID
    private lateinit var model: TextInputEditText
    private lateinit var endpoint: TextInputEditText
    private lateinit var key: TextInputEditText
    private lateinit var keyState: TextView
    private lateinit var models: TextView
    private lateinit var prompt: TextInputEditText
    private lateinit var tokens: TextInputEditText
    private lateinit var timeout: TextInputEditText
    private lateinit var history: TextInputEditText
    private var webSearch = true
    private lateinit var sttKey: TextInputEditText
    private lateinit var sttKeyState: TextView
    private lateinit var sttModel: TextInputEditText
    private lateinit var deepgramKey: TextInputEditText
    private lateinit var ambientModel: TextInputEditText
    private lateinit var deepgramState: TextView
    private var ambientProvider = org.turboio.customai.ambient.AmbientProvider.DEEPGRAM
    private var language = "vi-VN"
    private var ttsEngine = TtsEngine.GEMINI
    private var ttsVoice = "Kore"
    private var ttsOpenAiVoice = "alloy"
    private var ttsRate = 1.0f
    private lateinit var ttsModel: TextInputEditText
    private lateinit var ttsState: TextView
    private lateinit var geminiVoiceSpinner: View
    private lateinit var openAiVoiceSpinner: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val config = settings.aiConfig()
        provider = config.provider
        val ui = Column(requireContext())
        ui.header("AI", "Nhà cung cấp AI, giọng nói và chữ trên kính")

        ui.section("Nhà cung cấp AI")
        val g1 = ui.card()
        g1.spinner(ProviderKind.entries.toList(), ProviderKind.entries.indexOf(provider), { it.label }) { selected ->
            if (selected != provider) { provider = selected; loadProvider() }
        }
        model = g1.field("Model")
        models = g1.hint("")
        endpoint = g1.field("Địa chỉ API (HTTPS)")
        key = g1.field("Khóa API (để trống = giữ khóa đã lưu)", password = true)
        keyState = g1.hint("")
        g1.hint("Khóa được mã hóa bằng Android Keystore (AES-GCM), không bao giờ hiển thị, ghi log, sao lưu hay nằm trong APK.")

        ui.section("Cách trả lời")
        val g2 = ui.card()
        prompt = g2.field("Hướng dẫn hệ thống (system prompt)", config.systemPrompt, multiline = true)
        g2.button("Khôi phục hướng dẫn mặc định (tiếng Việt, trả lời ngắn)", filled = false) { prompt.setText(DEFAULT_SYSTEM_PROMPT) }
        tokens = g2.field("Giới hạn token của câu trả lời", config.maxTokens.toString(), number = true)
        timeout = g2.field("Thời gian chờ tối đa (giây)", config.timeoutSeconds.toString(), number = true)
        history = g2.field("Số lượt hội thoại được nhớ (0–10)", config.historyTurns.toString(), number = true)
        webSearch = config.webSearch
        g2.switch("Tìm trên internet khi cần (thời tiết, tin tức, giá cả)", webSearch) { webSearch = it }
        g2.hint("Gemini dùng Google Search; Claude dùng công cụ tìm kiếm web của Anthropic. Chỉ tìm khi câu hỏi cần, và nhà cung cấp " +
            "có thể tính phí mỗi lần tìm. OpenAI: dùng model có tìm kiếm (vd: gpt-4o-search-preview). " +
            "Ngày, giờ và thành phố thời tiết (tab Óculos/Kính) luôn được gửi kèm câu hỏi.")

        ui.section("Nhận dạng giọng nói")
        val g3 = ui.card()
        val stt = settings.sttConfig(); engine = stt.engine
        g3.spinner(SttEngine.entries.toList(), SttEngine.entries.indexOf(engine), { it.label }) { engine = it; loadStt() }
        language = stt.language
        g3.spinner(LANGUAGES, LANGUAGES.indexOfFirst { it.first == language }.coerceAtLeast(0), { "Ngôn ngữ nói: ${it.second}" }) { language = it.first }
        sttModel = g3.field("Model nhận dạng OpenAI", stt.openAiModel)
        sttKey = g3.field("Khóa API nhận dạng (để trống = giữ)", password = true)
        sttKeyState = g3.hint("")
        g3.hint("Mic của kính: âm thanh Opus nhận qua kênh chính thức của firmware gốc rồi gửi tới dịch vụ đã chọn. " +
            "Khuyên dùng “Mic kính → Gemini trực tiếp”: nghe và trả lời trong một lần gọi. “Mic điện thoại” cần dịch vụ nhận dạng của Google, máy không có Google có thể không chạy.")
        val voice = settings.voiceSettings()
        g3.switch("Bật trợ lý giọng nói (khi gọi trên kính)", voice.enabled) { settings.setVoiceEnabled(it) }
        g3.switch("Tiếp tục hội thoại sau câu trả lời (kính nghe tiếp)", voice.followUp) { settings.setVoiceFollowUp(it) }
        g3.switch("Bỏ dấu khi hiển thị (chỉ dùng nếu chữ bị ô vuông)", voice.stripAccents) { settings.setStripAccents(it) }
        val formats = listOf("legacy" to "Định dạng cũ, gửi lặp lại", "official" to "Định dạng của app chính thức", "bilingual" to "Chính thức, có dòng bản dịch",
            "caption" to "Phụ đề app chính thức 1.0.7 (tiêu đề)", "classic" to "Phụ đề app chính thức 1.0.7 (cổ điển)",
            "session_classic" to "Phiên phụ đề chính thức (cổ điển)", "session_title" to "Phiên phụ đề chính thức (tiêu đề)")
        g3.text("Định dạng chữ trên kính", size = 13f)
        g3.spinner(formats, formats.indexOfFirst { it.first == settings.displaySettings().textFormat }.coerceAtLeast(0), { it.second }) { settings.setTextFormat(it.first) }
        g3.text("Chữ trên kính (thử nghiệm; nếu kính từ chối sẽ về mặc định)", size = 13f)
        val d = settings.displaySettings()
        var fontSize = d.fontSize; var position = d.position; var lines = d.maxLines
        val sizes = listOf(1 to "Nhỏ", 2 to "Vừa (mặc định)", 3 to "Lớn")
        g3.spinner(sizes, sizes.indexOfFirst { it.first == fontSize }, { "Cỡ chữ: ${it.second}" }) { fontSize = it.first; settings.setDisplayLayout(fontSize, position, lines) }
        val positions = listOf("center" to "Giữa (mặc định)", "top" to "Trên", "bottom" to "Dưới")
        g3.spinner(positions, positions.indexOfFirst { it.first == position }, { "Vị trí: ${it.second}" }) { position = it.first; settings.setDisplayLayout(fontSize, position, lines) }
        g3.spinner(listOf(3, 4, 5), listOf(3, 4, 5).indexOf(lines).coerceAtLeast(0), { "Số dòng: $it${if (it == 5) " (mặc định)" else ""}" }) { lines = it; settings.setDisplayLayout(fontSize, position, lines) }

        ui.section("Đọc câu trả lời qua tai nghe")
        val g5 = ui.card()
        val tts = settings.ttsSettings(); ttsEngine = tts.engine; ttsVoice = tts.geminiVoice; ttsOpenAiVoice = tts.openAiVoice; ttsRate = tts.rate
        g5.spinner(TtsEngine.entries.toList(), TtsEngine.entries.indexOf(ttsEngine), { it.label }) { ttsEngine = it; loadTts() }
        ttsModel = g5.field("Model giọng Gemini", tts.geminiModel)
        geminiVoiceSpinner = g5.spinner(GEMINI_VOICES, GEMINI_VOICES.indexOfFirst { it.first == ttsVoice }.coerceAtLeast(0), { "Giọng: ${it.second}" }) { ttsVoice = it.first }
        openAiVoiceSpinner = g5.spinner(OPENAI_VOICES, OPENAI_VOICES.indexOf(ttsOpenAiVoice).coerceAtLeast(0), { "Giọng OpenAI: $it" }) { ttsOpenAiVoice = it }
        g5.spinner(RATES, RATES.indexOfFirst { it.first == ttsRate }.coerceAtLeast(2), { "Tốc độ đọc: ${it.second}" }) { ttsRate = it.first }
        ttsState = g5.hint("")
        g5.hint("Âm thanh phát ra thiết bị đang nối với điện thoại: tai nghe Bluetooth nếu có, nếu không thì loa điện thoại. " +
            "Câu trả lời nhiều ngôn ngữ được đọc đúng giọng từng câu. Giọng cloud lỗi thì tự chuyển sang giọng của điện thoại.")
        g5.buttons("Nghe thử" to {
            saveTts()
            TurboApp.instance.speaker.speak("Xin chào! Tôi là trợ lý trên kính của bạn. Hello, I can also speak English.")
            view?.postDelayed({ if (isAdded) loadTts() }, 6000)
        }, "Dừng đọc" to { TurboApp.instance.speaker.stop() })

        ui.section("Ghi âm môi trường (ghi liên tục)")
        val g4 = ui.card()
        val amb = settings.ambientSettings(); ambientProvider = amb.provider
        g4.spinner(org.turboio.customai.ambient.AmbientProvider.entries.toList(), org.turboio.customai.ambient.AmbientProvider.entries.indexOf(ambientProvider), { it.label }) { ambientProvider = it; loadAmbient() }
        deepgramKey = g4.field("Khóa Deepgram (để trống = giữ)", password = true)
        deepgramState = g4.hint("")
        ambientModel = g4.field("Model OpenAI cho ghi âm môi trường", amb.openAiModel)
        g4.hint("Deepgram Nova-3 tách người nói (tối đa ~5 phút mỗi lần gửi). OpenAI dùng khóa OpenAI và không tách người nói.")

        g4.button("Lưu cấu hình") { save() }
        loadProvider(); loadStt(); loadTts()
        return ui.scroll()
    }

    private fun loadProvider() {
        model.setText(settings.modelFor(provider))
        endpoint.setText(settings.endpointFor(provider).ifEmpty { provider.defaultEndpoint })
        endpoint.isEnabled = provider.endpointEditable
        models.text = "Gợi ý: " + provider.suggestedModels.joinToString(", ") + if (provider.endpointEditable) "\nVD: https://api.deepseek.com/chat/completions" else ""
        key.setText("")
        keyState.text = if (settings.hasApiKey(provider)) "Đã lưu khóa: ${Redactor.mask(settings.apiKey(provider))}" else "Chưa có khóa cho ${provider.label}"
    }

    /** Only the fields of the chosen services are shown. */
    private fun loadAmbient() {
        if (!::ambientModel.isInitialized) return
        val deepgram = ambientProvider == org.turboio.customai.ambient.AmbientProvider.DEEPGRAM || engine == SttEngine.GLASSES_DEEPGRAM
        deepgramKey.showField(deepgram)
        deepgramState.visibility = if (deepgram) View.VISIBLE else View.GONE
        deepgramState.text = if (settings.hasDeepgramKey()) "Đã lưu khóa Deepgram: ${Redactor.mask(settings.deepgramKey())}" else "Chưa có khóa Deepgram (console.deepgram.com → API Keys)"
        ambientModel.showField(ambientProvider == org.turboio.customai.ambient.AmbientProvider.OPENAI)
    }

    private fun loadStt() {
        sttModel.showField(engine == SttEngine.GLASSES_OPENAI)
        sttKey.showField(engine == SttEngine.GLASSES_OPENAI || engine == SttEngine.GLASSES_GOOGLE)
        sttKey.setText("")
        loadAmbient()
        sttKeyState.text = when {
            engine == SttEngine.PHONE_ANDROID -> "Không cần khóa"
            engine == SttEngine.GLASSES_GEMINI_DIRECT -> if (settings.aiConfig().provider != ProviderKind.GEMINI) "Hãy chọn Google Gemini làm nhà cung cấp AI (ở trên); nếu không, app chỉ dùng Gemini để chép lời rồi hỏi AI đã chọn"
                else if (settings.hasApiKey(ProviderKind.GEMINI)) "Âm thanh gửi thẳng tới Gemini (${settings.modelFor(ProviderKind.GEMINI)}), nghe và trả lời trong một lần gọi" else "Hãy lưu khóa Gemini ở mục nhà cung cấp AI (ở trên)"
            engine == SttEngine.GLASSES_GEMINI -> if (settings.hasApiKey(ProviderKind.GEMINI)) "Dùng khóa Gemini đã lưu (model ${settings.modelFor(ProviderKind.GEMINI)})" else "Hãy lưu khóa Gemini ở mục nhà cung cấp AI (ở trên)"
            engine == SttEngine.GLASSES_DEEPGRAM -> if (settings.hasDeepgramKey()) "Dùng khóa Deepgram ở mục ghi âm môi trường (bên dưới)" else "Nhập khóa Deepgram ở mục “Ghi âm môi trường” bên dưới"
            settings.hasSttKey(engine) -> "Đã lưu khóa: ${Redactor.mask(settings.sttKey(engine))}"
            engine == SttEngine.GLASSES_OPENAI && settings.hasApiKey(ProviderKind.OPENAI) -> "Sẽ dùng khóa OpenAI của nhà cung cấp AI"
            else -> "Chưa lưu khóa"
        }
    }

    private fun save() {
        val config = AiConfig(
            provider = provider,
            model = model.text.toString().trim(),
            endpoint = if (provider.endpointEditable) endpoint.text.toString().trim() else provider.defaultEndpoint,
            systemPrompt = prompt.text.toString(),
            maxTokens = tokens.text.toString().toIntOrNull() ?: 800,
            timeoutSeconds = timeout.text.toString().toIntOrNull() ?: 30,
            historyTurns = (history.text.toString().toIntOrNull() ?: 3).coerceIn(0, 10),
            webSearch = webSearch,
        )
        val newKey = key.text.toString().trim()
        val effectiveKey = newKey.ifEmpty { settings.apiKey(provider) }
        val error = ProviderCodec.validate(config, effectiveKey.ifEmpty { "placeholder-for-validation" })
        if (error != null) { Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show(); return }
        settings.saveAi(config)
        if (newKey.isNotEmpty()) settings.setApiKey(provider, newKey)
        settings.saveStt(settings.sttConfig().copy(engine = engine, language = language, openAiModel = sttModel.text.toString().trim().ifEmpty { "whisper-1" }))
        saveTts()
        settings.saveAmbient(settings.ambientSettings().copy(provider = ambientProvider,
            openAiModel = ambientModel.text.toString().trim().ifEmpty { "gpt-4o-mini-transcribe" }))
        deepgramKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.setDeepgramKey(it); deepgramKey.setText("") }
        val newStt = sttKey.text.toString().trim()
        if (newStt.isNotEmpty() && (engine == SttEngine.GLASSES_OPENAI || engine == SttEngine.GLASSES_GOOGLE)) settings.setSttKey(engine, newStt)
        DiagnosticLog.i("CFG", "configuração salva: provedor=${provider.id} modelo=${config.model} stt=${engine.id} chave=${if (effectiveKey.isEmpty()) "ausente" else "presente"}")
        TurboApp.instance.clearHistory()
        loadProvider(); loadStt()
        Toast.makeText(requireContext(), if (effectiveKey.isEmpty()) "Đã lưu. Còn thiếu khóa API." else "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
    }

    private fun saveTts() {
        settings.saveTts(settings.ttsSettings().copy(engine = ttsEngine, geminiModel = ttsModel.text.toString().trim().ifEmpty { "gemini-3.8-flash-lite-tts" },
            geminiVoice = ttsVoice, openAiVoice = ttsOpenAiVoice, rate = ttsRate))
    }

    private fun loadTts() {
        if (!::ttsState.isInitialized) return
        ttsModel.showField(ttsEngine == TtsEngine.GEMINI)
        geminiVoiceSpinner.visibility = if (ttsEngine == TtsEngine.GEMINI) View.VISIBLE else View.GONE
        openAiVoiceSpinner.visibility = if (ttsEngine == TtsEngine.OPENAI) View.VISIBLE else View.GONE
        val keyNote = when (ttsEngine) {
            TtsEngine.GEMINI -> if (settings.hasApiKey(ProviderKind.GEMINI)) "Dùng khóa Gemini đã lưu" else "Chưa có khóa Gemini: hãy lưu khóa ở mục nhà cung cấp AI (chọn Google Gemini)"
            TtsEngine.OPENAI -> if (settings.ttsOpenAiKey().isNotEmpty()) "Dùng khóa OpenAI đã lưu" else "Chưa có khóa OpenAI"
            TtsEngine.SYSTEM -> "Cần một bộ đọc văn bản (TTS) có tiếng Việt trên điện thoại"
            TtsEngine.OFF -> "Không đọc câu trả lời"
        }
        val last = TurboApp.instance.speaker.status
        ttsState.text = if (last.isNotEmpty()) "$keyNote\nLần gần nhất: $last" else keyNote
    }

    private companion object {
        val LANGUAGES = listOf("vi-VN" to "Tiếng Việt", "en-US" to "English", "zh-CN" to "中文", "ja-JP" to "日本語", "ko-KR" to "한국어",
            "fr-FR" to "Français", "pt-BR" to "Português")
        val GEMINI_VOICES = listOf("Kore" to "Kore (nữ, rõ ràng)", "Aoede" to "Aoede (nữ, nhẹ nhàng)", "Leda" to "Leda (nữ, trẻ)",
            "Puck" to "Puck (nam, vui vẻ)", "Charon" to "Charon (nam, trầm)", "Orus" to "Orus (nam, chắc chắn)", "Zephyr" to "Zephyr (sáng)")
        val OPENAI_VOICES = listOf("alloy", "nova", "shimmer", "coral", "echo", "onyx", "sage")
        val RATES = listOf(0.75f to "Chậm", 0.9f to "Hơi chậm", 1.0f to "Bình thường", 1.15f to "Hơi nhanh", 1.3f to "Nhanh")
    }
}
