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
import org.turboio.customai.translate.InterpreterEngine
import org.turboio.customai.translate.InterpreterSettings
import org.turboio.customai.translate.LiveWire
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
    private lateinit var liveModel: TextInputEditText
    private lateinit var sonioxKey: TextInputEditText
    private lateinit var sonioxState: TextView
    private lateinit var contextField: TextInputEditText
    private lateinit var glossaryField: TextInputEditText
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startNow() else toast("Cần quyền micro để dùng mic điện thoại")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Phiên dịch 2 chiều", "Nghe, dịch và đọc tự động giữa bạn và người đối diện")

        val g0 = ui.card()
        status = g0.text("", size = 15f)
        g0.buttons("Bắt đầu" to { start() }, "Dừng" to { app.interpreter.stop() }, "Xóa" to { app.interpreter.clear() })

        ui.section("Chế độ dịch")
        val ge = ui.card()
        val engines = InterpreterEngine.entries.toList()
        ge.spinner(engines, engines.indexOf(s.engine), { it.label }) { save(s.copy(engine = it)) }
        sonioxKey = ge.field("Khóa Soniox (để trống = giữ khóa đã lưu)", password = true)
        sonioxState = ge.hint(if (app.settings.sonioxKey().isNotBlank()) "Đã lưu khóa Soniox" else "Chưa có khóa Soniox – tạo ở console.soniox.com → API keys")
        contextField = ge.field("Ngữ cảnh (bạn là ai, nói chuyện với ai, về việc gì)", s.contextText, multiline = true)
        glossaryField = ge.field("Từ điển riêng – mỗi dòng: nguồn = đích (vd: 张总 = Giám đốc Trương)", s.glossary, multiline = true)
        ge.switch("Dịch lại mỗi câu bằng Gemini theo ngữ cảnh (chuẩn hơn, chậm hơn ~1 giây khi đọc)", s.refine) { save(s.copy(refine = it)) }
        ge.hint("Chuyên nghiệp: Soniox nghe và dịch 2 chiều liên tục, chữ dịch hiện trên kính khi người kia còn đang nói. Hết câu, Gemini dịch lại theo " +
            "ngữ cảnh và từ điển của bạn rồi mới đọc ra loa/tai nghe. Giá Soniox khoảng 0,18 USD/giờ.")
        liveModel = ge.field("Model Gemini Live Translate (chỉ cho chế độ Trực tiếp; để trống = ${LiveWire.DEFAULT_MODEL})", s.liveModel)
        ge.hint("Trực tiếp: giống phụ đề của app gốc – âm thanh được gửi liên tục, chữ dịch hiện ngay trên kính khi người kia còn đang nói " +
            "và giọng dịch phát gần như cùng lúc. Chỉ cần khóa Gemini. Nếu khóa của bạn chưa dùng được model này, app sẽ báo; khi đó chọn “Từng câu”.")

        ui.section("Hội thoại")
        val g1 = ui.card()
        conversation = g1.text("", size = 15f)

        ui.section("Ngôn ngữ")
        val g2 = ui.card()
        val langs = Languages.ALL
        g2.switch("Chế độ nghe: tự nhận mọi ngôn ngữ, dịch sang tiếng của tôi", s.listenMode) { save(s.copy(listenMode = it)) }
        g2.switch("Ghi nhãn người nói (A:, B:…) khi có nhiều người", s.labelSpeakers) { save(s.copy(labelSpeakers = it)) }
        g2.spinner(langs, langs.indexOfFirst { it.tag == s.myLanguage }.coerceAtLeast(0), { "Tôi nói / dịch sang: ${it.label}" }) { save(s.copy(myLanguage = it.tag)) }
        g2.spinner(langs, langs.indexOfFirst { it.tag == s.partnerLanguage }.coerceAtLeast(1), { "Người đối diện (chế độ 2 chiều): ${it.label}" }) { save(s.copy(partnerLanguage = it.tag)) }
        g2.hint("Chế độ nghe (khuyên dùng): bật sẵn, bạn chỉ cần chọn “dịch sang tiếng Việt”. Soniox tự nhận ra người kia nói tiếng Trung, " +
            "Anh hay xen lẫn, rồi dịch hết sang tiếng Việt; câu bạn nói không hiện. Tắt chế độ nghe để dịch 2 chiều: " +
            "lời bạn được dịch sang ngôn ngữ người đối diện và đọc ra loa, lời họ dịch sang tiếng bạn.")

        ui.section("Âm thanh")
        val g3 = ui.card()
        g3.spinner(InterpreterSource.entries.toList(), InterpreterSource.entries.indexOf(s.source), { "Nghe bằng: ${it.label}" }) { save(s.copy(source = it)) }
        g3.switch("Hiện bản dịch trên kính", s.showOnLens) { save(s.copy(showOnLens = it)) }
        g3.switch("Đọc lời tôi đã dịch ra loa điện thoại (cho người đối diện nghe)", s.speakToPartner) { save(s.copy(speakToPartner = it)) }
        g3.switch("Đọc lời người đối diện đã dịch vào tai nghe của tôi", s.speakToMe) { save(s.copy(speakToMe = it)) }
        val pauses = listOf(600 to "Ngắn (0,6 giây)", 800 to "Vừa (0,8 giây)", 1200 to "Dài (1,2 giây)")
        g3.spinner(pauses, pauses.indexOfFirst { it.first == s.silenceMs }.coerceAtLeast(1), { "Từng câu – ngắt câu khi im lặng: ${it.second}" }) { save(s.copy(silenceMs = it.first)) }
        g3.hint("Chế độ trực tiếp dùng giọng dịch của Gemini Live; chế độ từng câu dùng giọng ở tab AI. Khi đang phát bản dịch, micro tạm gửi im lặng để app không tự dịch lại giọng của chính nó. " +
            "Nếu không đeo tai nghe, nên tắt “đọc vào tai nghe” để loa điện thoại không lẫn hai giọng.")

        ui.section("Model AI cho chế độ từng câu")
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
            ProviderKind.GEMINI -> "Gemini nghe trực tiếp âm thanh (nhanh, chính xác nhất). Gợi ý: gemini-3.8-flash (dịch hay, khuyên dùng) hoặc gemini-3.5-flash-lite (nhanh, rẻ hơn)."
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
        val chosenLive = liveModel.text.toString().trim()
        save(s.copy(model = chosen, liveModel = chosenLive, contextText = contextField.text.toString().trim(), glossary = glossaryField.text.toString()))
        sonioxKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
            app.settings.setSonioxKey(it); sonioxKey.setText(""); sonioxState.text = "Đã lưu khóa Soniox"
        }
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

