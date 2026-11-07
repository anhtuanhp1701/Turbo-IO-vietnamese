package org.turboio.customai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.turboio.customai.TurboApp
import org.turboio.customai.ai.ProviderKind
import org.turboio.customai.translate.InterpreterSettings
import org.turboio.customai.translate.InterpreterSource
import org.turboio.customai.translate.InterpreterState
import org.turboio.customai.translate.Languages
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Two-way interpreter: settings, start/stop and the live conversation. */
class InterpreterFragment : Fragment() {
    private val app get() = TurboApp.instance
    private var s = app.settings.interpreterSettings()
    private lateinit var status: TextView
    private lateinit var conversation: TextView
    private lateinit var model: TextInputEditText
    private lateinit var modelHint: TextView
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startNow() else toast("Cần quyền micro để dùng mic điện thoại")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Phiên dịch 2 chiều", "Nghe, dịch và đọc tự động giữa bạn và người đối diện")

        val g0 = ui.card()
        status = g0.text("", size = 15f)
        g0.buttons("Bắt đầu" to { start() }, "Dừng" to { app.interpreter.stop() }, "Xóa" to { app.interpreter.clear() })

        ui.section("Hội thoại")
        val g1 = ui.card()
        conversation = g1.text("", size = 15f)

        ui.section("Ngôn ngữ")
        val g2 = ui.card()
        val langs = Languages.ALL
        g2.spinner(langs, langs.indexOfFirst { it.tag == s.myLanguage }.coerceAtLeast(0), { "Tôi nói: ${it.label}" }) { save(s.copy(myLanguage = it.tag)) }
        g2.spinner(langs, langs.indexOfFirst { it.tag == s.partnerLanguage }.coerceAtLeast(1), { "Người đối diện nói: ${it.label}" }) { save(s.copy(partnerLanguage = it.tag)) }
        g2.hint("App tự nhận ra ai đang nói theo ngôn ngữ: câu bằng ngôn ngữ của bạn được dịch sang ngôn ngữ người đối diện, " +
            "mọi ngôn ngữ khác được dịch sang ngôn ngữ của bạn.")

        ui.section("Âm thanh")
        val g3 = ui.card()
        g3.spinner(InterpreterSource.entries.toList(), InterpreterSource.entries.indexOf(s.source), { "Nghe bằng: ${it.label}" }) { save(s.copy(source = it)) }
        g3.switch("Hiện bản dịch trên kính", s.showOnLens) { save(s.copy(showOnLens = it)) }
        g3.switch("Đọc lời tôi đã dịch ra loa điện thoại (cho người đối diện nghe)", s.speakToPartner) { save(s.copy(speakToPartner = it)) }
        g3.switch("Đọc lời người đối diện đã dịch vào tai nghe của tôi", s.speakToMe) { save(s.copy(speakToMe = it)) }
        val pauses = listOf(600 to "Ngắn (0,6 giây)", 800 to "Vừa (0,8 giây)", 1200 to "Dài (1,2 giây)")
        g3.spinner(pauses, pauses.indexOfFirst { it.first == s.silenceMs }.coerceAtLeast(1), { "Ngắt câu khi im lặng: ${it.second}" }) { save(s.copy(silenceMs = it.first)) }
        g3.hint("Giọng đọc dùng cài đặt ở tab AI → “Đọc câu trả lời qua tai nghe”. Khi app đang đọc, micro tạm bỏ qua âm thanh để không tự dịch lại giọng của chính nó.")

        ui.section("Model AI dùng để dịch")
        val g4 = ui.card()
        val providers = ProviderKind.entries.toList()
        g4.spinner(providers, providers.indexOf(s.provider), { it.label }) { save(s.copy(provider = it)); loadModel() }
        model = g4.field("Model (để trống = dùng model đã lưu ở tab AI)", s.model)
        modelHint = g4.hint("")
        g4.button("Lưu model") { save(s.copy(model = model.text.toString().trim())); toast("Đã lưu model dịch"); loadModel() }

        loadModel()
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { app.interpreter.state.collect { render(it) } }
        }
    }

    private fun save(next: InterpreterSettings) {
        s = next; app.settings.saveInterpreter(next)
    }

    private fun loadModel() {
        val saved = app.settings.modelFor(s.provider)
        val key = if (app.settings.hasApiKey(s.provider)) "đã có khóa" else "CHƯA có khóa – lưu khóa ở tab AI"
        val tip = when (s.provider) {
            ProviderKind.GEMINI -> "Gemini nghe trực tiếp âm thanh (nhanh, chính xác nhất). Gợi ý: gemini-2.5-flash (nhanh) hoặc gemini-2.5-pro (dịch hay hơn, chậm hơn)."
            else -> "Model này dịch bản chép lời; phần chép lời dùng OpenAI (nếu có khóa) hoặc Gemini."
        }
        modelHint.text = "${s.provider.label}: $key. Model ở tab AI: $saved.\n$tip"
    }

    private fun start() {
        if (s.source == InterpreterSource.PHONE &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        startNow()
    }

    private fun startNow() {
        val chosen = model.text.toString().trim()
        if (chosen != s.model) save(s.copy(model = chosen))
        app.interpreter.start()?.let { toast(it) }
    }

    private fun render(state: InterpreterState) {
        status.text = (if (state.running) "● " else "○ ") + state.note
        status.setTextColor(statusColor(if (state.lastError.isNotEmpty() && !state.running) false else if (state.running) true else null))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        conversation.text = if (state.lines.isEmpty()) "Chưa có câu nào. Bấm “Bắt đầu” rồi nói chuyện bình thường."
        else state.lines.takeLast(20).asReversed().joinToString("\n\n") { line ->
            val who = if (line.fromMe) "Tôi" else "Đối phương"
            "${time.format(Date(line.at))} · $who\n${line.heard}\n➜ ${line.translation}"
        }
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}

