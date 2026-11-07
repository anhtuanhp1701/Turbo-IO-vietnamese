package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.display.DisplayState
import org.turboio.customai.display.DisplayText

class TestFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var question: TextInputEditText
    private lateinit var answer: TextView
    private lateinit var aiStatus: TextView
    private lateinit var lensStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var ask: MaterialButton
    private var lastAnswer = ""
    private lateinit var script: TextInputEditText
    private lateinit var prompterStatus: TextView
    private var prompterSpeed = 120
    private val openScript = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadScript(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Công cụ", "Hỏi AI và dùng máy nhắc chữ")
        ui.section("Hỏi AI")
        val g1 = ui.card()
        question = g1.field("Nhập câu hỏi (tiếng Việt hoặc ngôn ngữ khác)", multiline = true)
        ask = g1.button("Hỏi") { runQuery() }
        aiStatus = g1.text(size = 13f)
        ui.section("Câu trả lời")
        val g2 = ui.card()
        answer = g2.text("—")

        ui.section("Kính")
        val g3 = ui.card()
        g3.button("Gửi câu trả lời lên kính") {
            if (lastAnswer.isEmpty()) { lensStatus.text = "Hãy hỏi một câu trước"; lensStatus.setTextColor(statusColor(false)); return@button }
            app.display.show(lastAnswer, app.settings.displaySettings())
        }
        g3.button("Gửi chữ thử (dấu tiếng Việt)", filled = false) {
            app.display.show("Thử Turbo IO: xin chào! Tiếng Việt có dấu: ắ, ộ, ữ, đ, ỹ. Nếu thấy ô vuông, hãy bật \"Bỏ dấu\".", app.settings.displaySettings())
        }
        g3.button("Dừng hiển thị", filled = false) { app.display.stop() }
        lensStatus = g3.text(size = 13f)
        g3.button("Xóa ngữ cảnh hội thoại", filled = false) { app.clearHistory(); aiStatus.text = "Đã xóa ngữ cảnh" }

        ui.section("Trợ lý giọng nói")
        val g4 = ui.card()
        g4.hint("Gọi trợ lý trên kính (cử chỉ/nút AI của firmware gốc) rồi nói. App dùng bộ nhận dạng đã chọn ở tab AI.")
        voiceStatus = g4.text(size = 13f)

        ui.section("Máy nhắc chữ của kính")
        val g5 = ui.card()
        g5.hint("Dùng máy nhắc chữ có sẵn trên kính. Kịch bản được gửi dưới dạng tệp tạm (tối đa 12 000 ký tự) và " +
            "chỉ còn trên điện thoại sau khi bạn thoát. Không cài gì lên kính. Tính năng này chưa được xác nhận trên mọi kính.")
        script = g5.field("Kịch bản (dán văn bản)", multiline = true)
        g5.button("Mở tệp .txt", filled = false) { openScript.launch(arrayOf("text/plain")) }
        val speeds = (80..260 step 20).toList()
        g5.text("Tốc độ (từ mỗi phút)", size = 13f)
        g5.spinner(speeds, speeds.indexOf(prompterSpeed), { "$it" }) { prompterSpeed = it }
        var look = org.turboio.customai.device.Teleprompter.Look()
        val countdowns = listOf(0, 3, 5)
        g5.spinner(countdowns, countdowns.indexOf(look.countdown), { if (it == 0) "Bắt đầu không đếm ngược" else "Đếm ngược $it giây" }) { look = look.copy(countdown = it) }
        val sizes = listOf(18 to "pequeno", 20 to "vừa", 24 to "grande")
        g5.spinner(sizes, 0, { "Chữ ${it.second}" }) { look = look.copy(size = it.first) }
        val levels = listOf(1 to "perto", 2 to "vừa", 3 to "longe")
        g5.spinner(levels, 0, { "Độ sâu ${it.second}" }) { look = look.copy(depth = it.first) }
        val gears = listOf(1 to "baixo", 2 to "vừa", 3 to "alto")
        g5.spinner(gears, 0, { "Mức (gear) ${it.second}" }) { look = look.copy(gear = it.first) }
        val spacings = listOf(0 to "không thêm khoảng trắng", 4 to "normal", 8 to "largo")
        g5.spinner(spacings, 1, { "Giãn dòng ${it.second}" }) { look = look.copy(leading = it.first) }
        g5.button("Gửi kịch bản lên kính") { prompter { app.teleprompter.prepare(script.text.toString(), prompterSpeed, look) } }
        g5.buttons("Bắt đầu" to { prompter { app.teleprompter.control(3) } },
            "Tạm dừng" to { prompter { app.teleprompter.control(4) } },
            "Tiếp tục" to { prompter { app.teleprompter.control(5) } })
        g5.buttons("Áp dụng tốc độ" to { prompter { app.teleprompter.control(7, prompterSpeed) } },
            "Thoát" to { prompter { app.teleprompter.stop(); null } })
        g5.hint("Các kịch bản đã gửi (tối đa ${org.turboio.customai.device.ScriptLibrary.MAX}) được lưu trên điện thoại này và hiện trong menu " +
            "Máy nhắc chữ của kính; chọn một cái ở đó để mở.")
        g5.button("Xóa các kịch bản đã lưu", filled = false) {
            app.scripts.clear(); android.widget.Toast.makeText(requireContext(), "Đã xóa các kịch bản", android.widget.Toast.LENGTH_SHORT).show()
        }
        prompterStatus = g5.text(size = 13f)
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.display.state.collect { renderLens(it) } }
                launch {
                    app.teleprompter.state.collect { t ->
                        prompterStatus.text = "Trạng thái: ${t.phase.label} · ${t.note}" +
                            (if (t.progress in 1..99) "\nĐã gửi: ${t.progress}%" else "") + "\nTốc độ: ${t.speed}"
                        prompterStatus.setTextColor(statusColor(when (t.phase) {
                            org.turboio.customai.device.Teleprompter.Phase.ERROR -> false
                            org.turboio.customai.device.Teleprompter.Phase.READY, org.turboio.customai.device.Teleprompter.Phase.RUNNING -> true
                            else -> null
                        }))
                    }
                }
                launch {
                    app.voice.state.collect { v ->
                        voiceStatus.text = "Trạng thái: ${v.phase} · ${v.note}" +
                            (if (v.transcript.isNotEmpty()) "\nBạn: ${v.transcript}" else "") +
                            (if (v.answer.isNotEmpty()) "\nAI: ${v.answer.take(300)}" else "") +
                            (if (v.audioPackets > 0) "\nÂm thanh từ kính: ${v.audioPackets} gói / ${v.audioMs} ms" else "") +
                            "\nSố lượt đã xong: ${v.rounds}"
                        voiceStatus.setTextColor(statusColor(if (v.lastError.isNotEmpty() && v.note == v.lastError) false else null))
                    }
                }
            }
        }
    }

    private fun renderLens(s: DisplayState) {
        val (text, ok) = when (s) {
            DisplayState.Idle -> "Hiển thị: không hoạt động" to null
            is DisplayState.Opening -> "Đang mở hiển thị (${s.pages} trang)…" to null
            is DisplayState.Showing -> "✓ Đã gửi trang ${s.page}/${s.pages}: \"${s.text}\"" to true
            is DisplayState.Done -> "✓ Hiển thị xong (${s.pages} trang). Hãy kiểm tra bằng mắt trên kính." to true
            is DisplayState.Failed -> "✗ ${s.reason}" to false
        }
        lensStatus.text = text; lensStatus.setTextColor(statusColor(ok))
    }

    private fun prompter(action: suspend () -> String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            action()?.let { prompterStatus.text = "✗ $it"; prompterStatus.setTextColor(statusColor(false)) }
        }
    }

    private fun loadScript(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val limit = org.turboio.customai.protocol.FileTransfer.MAX_FILE
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                        while (out.size() <= limit) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        if (out.size() > limit) null else out.toString("UTF-8")
                    }
                }.getOrNull()
            }
            if (text == null) { prompterStatus.text = "✗ Không đọc được tệp hoặc tệp lớn hơn 48 KB"; prompterStatus.setTextColor(statusColor(false)) }
            else script.setText(text)
        }
    }

    private fun runQuery() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) { aiStatus.text = "Hãy nhập câu hỏi"; return }
        ask.isEnabled = false
        aiStatus.text = "Đang hỏi ${app.settings.aiConfig().provider.label}…"; aiStatus.setTextColor(statusColor(null))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Questions about the journal ("resumo do meu dia", "o que falei sobre X") are answered from it.
                app.journalAnswer(q, org.turboio.customai.journal.EntrySource.TEXT)?.let { fromJournal ->
                    lastAnswer = fromJournal
                    answer.text = DisplayText.clean(fromJournal, app.settings.displaySettings().stripAccents)
                    app.speaker.speak(fromJournal)
                    aiStatus.text = "✓ Đã trả lời từ nhật ký"; aiStatus.setTextColor(statusColor(true))
                    return@launch
                }
                val result = app.askAi(q)
                lastAnswer = result.text
                answer.text = DisplayText.clean(result.text, app.settings.displaySettings().stripAccents)
                app.speaker.speak(result.text)
                aiStatus.text = "✓ ${result.provider.label} · ${result.model} · ${result.latencyMs} ms · ${result.text.length} ký tự" +
                    if (result.truncated) " · câu trả lời bị cắt do giới hạn token" else ""
                aiStatus.setTextColor(statusColor(true))
            } catch (e: Exception) {
                aiStatus.text = "✗ " + TurboApp.friendly(e); aiStatus.setTextColor(statusColor(false))
            } finally { ask.isEnabled = true }
        }
    }
}
