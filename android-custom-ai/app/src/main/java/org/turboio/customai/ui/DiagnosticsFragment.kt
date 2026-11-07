package org.turboio.customai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.turboio.customai.BuildConfig
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.service.ConnectionService

class DiagnosticsFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var info: TextView
    private lateinit var test: TextView
    private lateinit var log: TextView
    private lateinit var probeStatus: TextView
    private lateinit var suiteText: TextView
    private lateinit var cueText: TextView
    private lateinit var btnBegin: View
    private lateinit var btnStep: View
    private lateinit var btnStopStep: View
    private lateinit var btnSkip: View
    private lateinit var btnExportAll: View
    private lateinit var btnEnd: View
    private lateinit var featText: TextView
    private lateinit var featCue: TextView
    private lateinit var featNote: com.google.android.material.textfield.TextInputEditText
    private val featButtons = HashMap<String, View>()
    private val refresh: () -> Unit = { view?.post { renderLog() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Chẩn đoán", "Bài thử trên kính, log và dữ liệu")
        ui.section("Phiên bản và kết nối")
        val g1 = ui.card()
        info = g1.text(size = 13f, mono = true)

        ui.section("Thử giao tiếp")
        val g2 = ui.card()
        g2.button("Thử BLE: đọc trạng thái kính") { bleTest() }
        g2.button("Thử nhà cung cấp AI (câu hỏi ngắn)", filled = false) { aiTest() }
        test = g2.text(size = 13f)

        ui.section("Bộ kiểm tra tính năng (mọi chức năng)")
        ui.hint("Thử từng chức năng trên kính, lần lượt: app thực hiện thao tác, hiện những gì nó thấy và bạn cho biết trên kính có chạy không. " +
            "Dữ liệu trao đổi với kính được ghi lại (không có âm thanh hay nội dung thông báo). Cuối cùng, xuất tệp và gửi cho tôi.")
        val fc = ui.card()
        featText = fc.text(size = 14f)
        featCue = fc.text(size = 20f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        featNote = fc.field("Nhận xét (tùy chọn): điều gì đã hiện, thông báo lỗi…", multiline = true)
        val f = app.features
        featButtons["begin"] = fc.button("Bắt đầu bộ kiểm tra tính năng") { f.begin() }
        featButtons["start"] = fc.button("Bắt đầu bước này") {
            // The recording step needs the ambient-recording consent; ask for it here instead of failing.
            if (f.current?.id == "F19" && !app.settings.ambientSettings().consent) {
                AlertDialog.Builder(requireContext()).setTitle("Trước khi ghi âm môi trường")
                    .setMessage("Bản ghi thu giọng bạn và những người xung quanh. Hãy báo cho người đi cùng và tôn trọng quy định về quyền riêng tư nơi bạn ở. " +
                        "Chỉ những đoạn có tiếng nói được gửi tới dịch vụ chép lời đã cấu hình; âm thanh bị xóa sau đó.")
                    .setPositiveButton("Tôi đã hiểu và đồng ý") { _, _ ->
                        app.settings.saveAmbient(app.settings.ambientSettings().copy(consent = true))
                        f.start()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
                    }.setNegativeButton("Hủy", null).show()
                return@button
            }
            f.start()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
        featButtons["stop"] = fc.button("Dừng bước này", filled = false) { f.stop() }
        featButtons["verdict"] = fc.buttons("✓ Chạy tốt" to { verdict(true) }, "✗ Không chạy" to { verdict(false) })
        featButtons["unsure"] = fc.buttons("Không xem được" to { verdict(null) }, "Làm lại" to { f.repeatStep() })
        featButtons["skip"] = fc.button("Bỏ qua bước", filled = false) { f.skip() }
        featButtons["export"] = fc.button("Xuất bộ kiểm tra tính năng (1 tệp)") { exportFeatures() }
        featButtons["end"] = fc.button("Kết thúc bộ kiểm tra tính năng", filled = false) { f.end() }

        ui.section("Bộ kiểm tra âm thanh của kính (LifeLog)")
        ui.hint("Tất cả bài thử phần cứng theo thứ tự (khoảng 10 phút), có hướng dẫn giữa các bước, và một tệp duy nhất " +
            "để gửi ở cuối. Không ghi gì vào firmware; LifeLog được tắt sau mỗi bước.")
        val suiteCard = ui.card()
        suiteText = suiteCard.text(size = 14f)
        cueText = suiteCard.text(size = 20f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        btnBegin = suiteCard.button("Bắt đầu bộ kiểm tra") { confirmProbe("bộ kiểm tra đầy đủ (≈ 10 phút)") { app.suite.begin(); null } }
        btnStep = suiteCard.button("Bắt đầu bước này") { app.suite.startStep()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
        btnStopStep = suiteCard.button("Dừng bước này", filled = false) { app.probe.stop() }
        btnSkip = suiteCard.button("Bỏ qua bước", filled = false) { app.suite.skip() }
        btnExportAll = suiteCard.button("Xuất tất cả bài thử (1 tệp)") { exportAll() }
        btnEnd = suiteCard.button("Kết thúc bộ kiểm tra", filled = false) { app.suite.end() }

        ui.section("Các bài thử phần cứng riêng lẻ (giai đoạn 3)")
        val g3 = ui.card()
        g3.hint("Các bài thử ngắn, có giới hạn trên kênh âm thanh của firmware gốc. Không ghi gì vào firmware. " +
            "Hãy nói các câu thử và báo cho người xung quanh. Cuối cùng, xuất gói và gửi đi phân tích.")
        g3.button("G1–G3 · LifeLog 30 giây") { confirmProbe("LifeLog trong 30 giây") { app.probe.startLifeLog(30) } }
        g3.button("G4 · LifeLog 5 phút (nói có ngắt nghỉ)", filled = false) { confirmProbe("LifeLog trong 5 phút") { app.probe.startLifeLog(300) } }
        g3.button("G4b · LifeLog thời gian thực 60 giây", filled = false) { confirmProbe("LifeLog thời gian thực trong 60 giây") { app.probe.startLifeLog(60, realtimePage = true) } }
        g3.button("Dừng bài thử", filled = false) { app.probe.stop() }
        g3.button("An toàn: tắt LifeLog trên kính", filled = false) { app.probe.forceLifeLogOff() }
        probeStatus = g3.text(size = 12.5f, mono = true)
        g3.button("Xuất bài thử gần nhất (có âm thanh)", filled = false) { exportProbe(true) }
        g3.button("Xuất bài thử gần nhất (không âm thanh)", filled = false) { exportProbe(false) }
        g3.button("Xóa các bài thử đã lưu trên điện thoại", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Xóa các bài thử đã lưu?")
                .setMessage("Xóa âm thanh và báo cáo của các bài thử phần cứng được lưu trong app này.")
                .setPositiveButton("Xóa") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.deleteAll() }
                    Toast.makeText(requireContext(), "Đã xóa các bài thử", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Hủy", null).show()
        }

        ui.section("Log kỹ thuật (không chứa khóa, địa chỉ hay nội dung)")
        val row = ui.row()
        val c = Column(requireContext())
        c.button("Sao chép log", filled = false) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Turbo IO log", header() + "\n" + DiagnosticLog.export()))
            Toast.makeText(requireContext(), "Đã sao chép log", Toast.LENGTH_SHORT).show()
        }
        c.button("Xóa log", filled = false) { DiagnosticLog.clear() }
        row.addView(c.root)
        log = ui.text(size = 11f, mono = true)

        ui.section("Hoàn tác")
        val g4 = ui.card()
        g4.button("Xóa toàn bộ dữ liệu của app này", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Xóa dữ liệu?")
                .setMessage("Xóa khóa API, cài đặt, nhật ký, bộ nhớ (người và việc) và kính đã lưu của app này. App chính thức và firmware không bị ảnh hưởng.")
                .setPositiveButton("Xóa") { _, _ ->
                    if (app.settings.ambientSettings().glassesMenu) {   // tell the glasses the phone-side LifeLog setup is gone
                        app.settings.saveAmbient(app.settings.ambientSettings().copy(glassesMenu = false)); app.ambient.armGlassesMenu()
                    }
                    app.session.forget(); app.settings.wipe(); app.clearHistory()
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.journal.wipe() }; runCatching { app.memoryStore.wipe() } }
                    app.ambient.stop(); app.ambient.wipe(); runCatching { app.scripts.clear() }
                    ConnectionService.stop(requireContext())
                    DiagnosticLog.i("APP", "dados locais apagados pelo usuário")
                }.setNegativeButton("Hủy", null).show()
        }
        return ui.scroll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DiagnosticLog.addListener(refresh)
        renderLog()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.status.collect { info.text = header() } }
                launch { app.features.state.collect { renderFeatures(it) } }
                launch { kotlinx.coroutines.flow.combine(app.suite.state, app.probe.state) { a, b -> a to b }.collect { (s, p) -> renderSuite(s, p) } }
                launch {
                    app.probe.state.collect { p ->
                        probeStatus.text = buildString {
                            append("Bài thử: ${p.kind.ifEmpty { "—" }} · ${p.phase}")
                            if (p.secondsLeft > 0) append(" · ${p.secondsLeft} s")
                            append("\n${p.note}\nGói tin: ${p.records} · âm thanh ${p.audioBytes / 1024} KB")
                            if (p.pendingOff) append("\n⚠ LifeLog có thể vẫn đang BẬT trên kính — chưa xác nhận tắt")
                            else if (p.offConfirmed) append("\n✓ Đã tắt LifeLog (kính đã xác nhận)")
                            if (p.verdict.isNotEmpty()) append("\nKết quả: ${p.verdict}")
                            if (p.savedAt.isNotEmpty()) append("\nĐã lưu: ${p.savedAt}")
                            if (p.events.isNotEmpty()) append("\n— sự kiện —\n").append(p.events.takeLast(12).joinToString("\n"))
                        }
                        probeStatus.setTextColor(statusColor(if (p.pendingOff) false else if (p.phase == "concluído") true else null))
                    }
                }
            }
        }
    }

    override fun onDestroyView() { DiagnosticLog.removeListener(refresh); super.onDestroyView() }

    private fun header(): String {
        val s = app.status.value
        val ai = app.settings.aiConfig()
        val official = try { requireContext().packageManager.getPackageInfo("com.rayneo.venus", 0).versionName } catch (_: Exception) { "chưa cài" }
        return """
            App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE}/API ${Build.VERSION.SDK_INT}
            Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}
            App RayNeo oficial: $official
            Fase: ${s.phase} · retries ${s.retries} · motivo: ${s.lastReason.ifEmpty { "—" }}
            Protocolo: v${s.protocolVersion} · campos versão: ${s.versionShape.ifEmpty { "—" }}
            Óculos (status): ${s.glassesVersions.ifEmpty { "—" }}
            Chaves do status: ${s.statusKeys.ifEmpty { "—" }}
            MTU ${s.mtu} · TX ${s.txBytes} B / ${s.framesOut} quadros · RX ${s.rxBytes} B / ${s.framesIn} quadros
            Respostas de status: ${s.statusReplies} · bateria ${if (s.battery >= 0) "${s.battery}%" else "—"}
            Óculos salvo: ${if (PrefsPeerStore(requireContext()).load() != null) "sim" else "não"}
            IA: ${ai.provider.id}/${ai.model} · chave ${if (app.settings.hasApiKey(ai.provider)) "presente" else "ausente"}
            Voz: ${app.settings.sttConfig().engine.id} · ${app.voice.state.value.phase}
        """.trimIndent()
    }

    private fun confirmProbe(what: String, start: () -> String?) {
        AlertDialog.Builder(requireContext()).setTitle("Bắt đầu bài thử: $what?")
            .setMessage("Bài thử thu âm thanh từ kính và lưu trên điện thoại để phân tích. Hãy dùng câu thử và báo cho người xung quanh. " +
                "Không thay đổi firmware; LifeLog được tắt khi kết thúc (và tắt lại ở lần kết nối sau nếu cần).")
            .setPositiveButton("Bắt đầu") { _, _ -> start()?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
            .setNegativeButton("Hủy", null).show()
    }

    private fun exportProbe(audio: Boolean) {
        val dir = app.probeFiles.latest() ?: run { Toast.makeText(requireContext(), "Chưa lưu bài thử nào", Toast.LENGTH_SHORT).show(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.share(dir, audio, header() + "\n\n" + DiagnosticLog.export()) }
            startActivity(intent)
        }
    }

    private fun renderSuite(s: org.turboio.customai.probe.SuiteState, p: org.turboio.customai.probe.ProbeState) {
        val steps = org.turboio.customai.probe.TestSuite.STEPS
        val step = s.current
        suiteText.text = buildString {
            if (!s.active && s.results.isEmpty()) { append("Bấm “Bắt đầu bộ kiểm tra”. Giữ kính kết nối và đủ pin."); return@buildString }
            if (s.active && step != null) {
                append("Bước ${s.index + 1}/${steps.size} · ${step.id} · ${step.title}\n${step.instructions}")
                if (s.running) {
                    append("\n\n${p.phase}")
                    if (p.secondsLeft > 0) append(" · ${p.secondsLeft} s")
                    if (p.audioSeconds >= 0) append(" · âm thanh: ${p.audioSeconds} giây")
                    if (p.trigger.isNotEmpty()) append("\n${p.trigger}")
                    if (p.pendingOff && p.phase == "inativo") append("\n⚠ LifeLog có thể đang bật")
                }
            } else if (s.active) append("✓ Đã hoàn tất bộ kiểm tra. Bấm “Xuất tất cả bài thử” và gửi tệp cho tôi.")
            else append("Đã kết thúc bộ kiểm tra.")
            if (s.results.isNotEmpty()) {
                append("\n\nKết quả:")
                s.results.forEach { append("\n${if (it.done) "✓" else "↷"} ${it.id}: ${it.verdict.take(90)}") }
            }
        }
        cueText.text = if (s.running) step?.cue(p.audioSeconds) ?: "" else ""
        cueText.visibility = if (cueText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(btnBegin, !s.active)
        show(btnStep, s.active && !s.running && step != null)
        show(btnSkip, s.active && !s.running && step != null)
        show(btnStopStep, s.active && s.running && p.phase != "inativo")
        show(btnExportAll, s.results.any { it.done })
        show(btnEnd, s.active)
    }

    private fun verdict(worked: Boolean?) {
        app.features.verdict(worked, featNote.text.toString()); featNote.setText("")
    }

    private fun renderFeatures(s: org.turboio.customai.probe.FeatureState) {
        val f = app.features
        val step = f.current
        featText.text = buildString {
            if (!s.active && s.results.isEmpty()) { append("Bấm “Bắt đầu”. Kính đã kết nối, đang đeo và đủ pin. ${f.steps.size} bước, khoảng 20 phút; có thể bỏ qua bước không muốn làm."); return@buildString }
            if (s.active && step != null) {
                append("Bước ${s.index + 1}/${f.steps.size} · ${step.id} · ${step.title}\n${step.instructions}")
                when (s.phase) {
                    org.turboio.customai.probe.FeaturePhase.RUNNING -> append("\n\nĐang chạy…")
                    org.turboio.customai.probe.FeaturePhase.VERDICT -> append("\n\nKết quả của app:\n${s.outcome}\n\n${step.question}")
                    org.turboio.customai.probe.FeaturePhase.IDLE -> Unit
                }
            } else if (s.active) append("✓ Đã hoàn tất bộ kiểm tra. Bấm “Xuất bộ kiểm tra tính năng” và gửi tệp cho tôi.")
            else append("Đã kết thúc bộ kiểm tra tính năng.")
            if (s.results.isNotEmpty()) {
                append("\n\nKết quả:")
                s.results.forEach { r -> append("\n${when (r.verdict) { "funcionou" -> "✓"; "không hoạt động" -> "✗"; "pulado" -> "↷"; else -> "?" }} ${r.id} ${r.title}: ${r.verdict}") }
            }
        }
        featCue.text = s.cue
        featCue.visibility = if (s.cue.isEmpty()) View.GONE else View.VISIBLE
        fun show(key: String, on: Boolean) { featButtons[key]?.visibility = if (on) View.VISIBLE else View.GONE }
        val waiting = s.active && step != null
        show("begin", !s.active)
        show("start", waiting && s.phase == org.turboio.customai.probe.FeaturePhase.IDLE)
        show("stop", s.phase == org.turboio.customai.probe.FeaturePhase.RUNNING)
        show("verdict", s.phase == org.turboio.customai.probe.FeaturePhase.VERDICT); show("unsure", s.phase == org.turboio.customai.probe.FeaturePhase.VERDICT)
        featNote.showField(s.phase == org.turboio.customai.probe.FeaturePhase.VERDICT)
        show("skip", waiting && s.phase != org.turboio.customai.probe.FeaturePhase.RUNNING)
        show("export", s.results.any { it.verdict != "pulado" })
        show("end", s.active)
    }

    private fun exportFeatures() {
        val f = app.features
        val dirs = app.probeFiles.featureDirs(f.state.value.startedAt)
        if (dirs.isEmpty()) { Toast.makeText(requireContext(), "Chưa hoàn tất bước nào từ khi bắt đầu bộ kiểm tra", Toast.LENGTH_SHORT).show(); return }
        val summary = header() + "\n\n" + f.summary()
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.probeFiles.shareAll(dirs, summary, DiagnosticLog.export(), prefix = "turboio-recursos")
            }
            startActivity(intent)
        }
    }

    private fun exportAll() {
        val s = app.suite.state.value
        val dirs = app.probeFiles.all(since = s.startedAt)
        if (dirs.isEmpty()) { Toast.makeText(requireContext(), "Chưa lưu bài thử nào từ khi bắt đầu bộ kiểm tra", Toast.LENGTH_SHORT).show(); return }
        val summary = header() + "\n\nBộ kiểm tra bắt đầu lúc ${java.util.Date(s.startedAt)}\n" +
            s.results.joinToString("\n") { "${if (it.done) "✓" else "↷"} ${it.id}: ${it.verdict} ${it.savedAt}" }
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.probeFiles.shareAll(dirs, summary, DiagnosticLog.export()) }
            startActivity(intent)
        }
    }

    private fun renderLog() {
        if (!isAdded) return
        log.text = DiagnosticLog.snapshot().takeLast(300).asReversed().joinToString("\n") { it.format() }
    }

    private fun bleTest() {
        if (!app.session.isReady) { test.text = "✗ Chưa kết nối kính"; test.setTextColor(statusColor(false)); return }
        val before = app.status.value.statusReplies
        test.text = "Đang gửi truy vấn trạng thái (chỉ đọc)…"; test.setTextColor(statusColor(null))
        app.session.requestStatus { error ->
            view?.post {
                if (error != null) { test.text = "✗ Gửi thất bại: $error"; test.setTextColor(statusColor(false)); return@post }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeat(30) { if (app.status.value.statusReplies > before) return@repeat; delay(100) }
                    val ok = app.status.value.statusReplies > before
                    test.text = if (ok) "✓ Kính đã phản hồi (pin ${app.status.value.battery}%)" else "✗ Không có phản hồi trong 3 giây"
                    test.setTextColor(statusColor(ok))
                }
            }
        }
    }

    private fun aiTest() {
        test.text = "Đang hỏi nhà cung cấp…"; test.setTextColor(statusColor(null))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = app.askAi("Bài thử chẩn đoán: chỉ trả lời đúng một từ OK.", record = false)
                test.text = "✓ ${r.provider.label}/${r.model} đã trả lời trong ${r.latencyMs} ms: \"${r.text.take(60)}\""
                test.setTextColor(statusColor(true))
            } catch (e: Exception) {
                test.text = "✗ " + TurboApp.friendly(e); test.setTextColor(statusColor(false))
            }
        }
    }
}
