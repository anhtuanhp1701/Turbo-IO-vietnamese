package org.turboio.customai.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.insights.DailyInsightWorker
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.journal.EntryKind
import org.turboio.customai.journal.JournalExport

class JournalFragment : Fragment() {
    private val app get() = TurboApp.instance
    private var day = Days.of(System.currentTimeMillis())
    private lateinit var dayLabel: TextView
    private lateinit var insightText: TextView
    private lateinit var status: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var search: TextInputEditText
    private lateinit var generate: MaterialButton
    private var lastLens = ""
    private lateinit var ambientStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.buttons("‹  Trước" to { day = Days.shift(day, -1); load() }, "Hôm nay" to { day = Days.of(System.currentTimeMillis()); load() },
            "Sau  ›" to { day = Days.shift(day, 1); load() })
        dayLabel = ui.title("")

        val amb = ui.card()
        amb.title("Ghi âm môi trường (thử nghiệm)")
        ambientStatus = amb.text("", size = 13f)
        amb.hint("Bằng kính: dùng micro của kính (LifeLog của firmware gốc) và tách giọng bạn với người khác. " +
            "Bằng điện thoại: dùng micro của điện thoại.")
        amb.button("Ghi bằng kính") { startAmbient(glasses = true) }
        amb.button("Ghi bằng micro điện thoại", filled = false) { startAmbient(glasses = false) }
        amb.button("Dừng ghi", filled = false) { app.ambient.stop() }
        amb.button("Chép lời các đoạn đang chờ ngay", filled = false) { org.turboio.customai.ambient.AmbientRecorder.enqueue(app); app.ambient.refresh() }
        amb.button("Thử lại các đoạn bị lỗi", filled = false) { app.ambient.retryFailed() }
        amb.button("Pin: cho phép chạy nền (Honor/Samsung: \"Không hạn chế\")", filled = false) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        val a = app.settings.ambientSettings()
        amb.switch("Xóa âm thanh sau khi chép lời", a.deleteRaw) { app.settings.saveAmbient(app.settings.ambientSettings().copy(deleteRaw = it)) }
        amb.switch("Chỉ chép lời khi có Wi-Fi", a.wifiOnly) { app.settings.saveAmbient(app.settings.ambientSettings().copy(wifiOnly = it)) }
        amb.switch("Bật và tắt từ menu của kính", a.glassesMenu) { on ->
            if (on && !app.settings.ambientSettings().consent) toast("Hãy đồng ý thông báo quyền riêng tư (bấm Ghi bằng kính) để menu của kính hoạt động")
            app.settings.saveAmbient(app.settings.ambientSettings().copy(glassesMenu = on)); app.ambient.armGlassesMenu()
        }
        amb.hint("Với tùy chọn này, mở LifeLog trong menu kính sẽ bắt đầu ghi nhật ký (khi app đang mở hoặc kết nối được giữ ở nền), " +
            "và đóng nó trên kính sẽ dừng ghi. Hãy tắt trước khi gỡ app.")

        val card = ui.card()
        card.title("Phân tích trong ngày")
        insightText = card.text("—")
        generate = card.button("Tạo / cập nhật phân tích") { generate(force = false) }
        card.button("Gửi tóm tắt lên kính", filled = false) {
            if (lastLens.isEmpty()) toast("Hãy tạo phân tích trước") else app.display.show(lastLens, app.settings.displaySettings())
        }
        card.button("Làm lại từ đầu (bỏ qua bộ nhớ đệm)", filled = false) { generate(force = true) }
        status = ui.hint("")

        ui.section("Tìm trong nhật ký")
        val g1 = ui.card()
        search = g1.field("Từ khóa (vd: họp ngân sách)")
        g1.button("Tìm trong tất cả các ngày", filled = false) { runSearch() }

        ui.section("Dòng thời gian")
        val g2 = ui.card()
        timeline = g2.row().apply { orientation = LinearLayout.VERTICAL }

        ui.section("Thao tác")
        val g3 = ui.card()
        g3.button("Xuất ngày này (Markdown)", filled = false) { export() }
        g3.button("Xóa ngày này", filled = false) { confirmDeleteDay() }

        ui.section("Cài đặt nhật ký")
        val g4 = ui.card()
        val s = app.settings.journalSettings()
        g4.switch("Ghi câu hỏi và câu trả lời vào nhật ký", s.recordInteractions) { app.settings.saveJournal(app.settings.journalSettings().copy(recordInteractions = it)) }
        g4.switch("Tự động tóm tắt mỗi ngày", s.autoDaily) { app.settings.saveJournal(app.settings.journalSettings().copy(autoDaily = it)); DailyInsightWorker.schedule(requireContext()) }
        val time = g4.field("Giờ tóm tắt (HH:MM)", "%02d:%02d".format(s.dailyHour, s.dailyMinute))
        g4.switch("Hiện tóm tắt tự động trên kính (nếu đã kết nối)", s.lensDaily) { app.settings.saveJournal(app.settings.journalSettings().copy(lensDaily = it)) }
        val retention = g4.field("Giữ số ngày (0 = mãi mãi)", s.retentionDays.toString(), number = true)
        val model = g4.field("Model cho phân tích (để trống = giống tab AI)", s.insightsModel)
        g4.button("Lưu cài đặt nhật ký", filled = false) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.text.toString().trim())
            val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) { toast("Giờ không hợp lệ (dùng HH:MM)"); return@button }
            app.settings.saveJournal(app.settings.journalSettings().copy(dailyHour = h, dailyMinute = mi,
                retentionDays = retention.text.toString().toIntOrNull()?.coerceIn(0, 3650) ?: 0, insightsModel = model.text.toString().trim()))
            DailyInsightWorker.schedule(requireContext()); toast("Đã lưu cài đặt")
        }
        g4.hint("Mọi thứ nằm trên điện thoại. Để tạo phân tích, văn bản trong ngày được gửi tới nhà cung cấp AI đã cấu hình.")
        // Sections: Dia (this column) · Pendências · Pessoas · Semana (memory, docs/MEMORIA_E_ASSISTENTE.md).
        memory = MemoryViews(requireContext()) { viewLifecycleOwner.lifecycleScope }
        val dayView = ui.scroll()
        val views = listOf(dayView, memory.pendingView, memory.peopleView, memory.weekView)
        val tabs = com.google.android.material.button.MaterialButtonToggleGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
        val labels = listOf("Ngày", "Việc cần làm", "Mọi người", "Tuần")
        val ids = labels.map { View.generateViewId() }
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        labels.forEachIndexed { i, label ->
            tabs.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ids[i]; text = label; isAllCaps = false; textSize = 13.5f; letterSpacing = 0f; setPadding(0, paddingTop, 0, paddingBottom)
                minHeight = ui.dp(44); insetTop = 0; insetBottom = 0
                backgroundTintList = android.content.res.ColorStateList(checkedStates, intArrayOf(Palette.PRIMARY_CONTAINER, Palette.SURFACE))
                setTextColor(android.content.res.ColorStateList(checkedStates, intArrayOf(Palette.PRIMARY, Palette.MUTED)))
                strokeColor = android.content.res.ColorStateList.valueOf(Palette.STROKE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val frame = android.widget.FrameLayout(requireContext())
        views.forEach { frame.addView(it, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        tabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val index = ids.indexOf(id)
            views.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
            when (index) { 1 -> memory.loadPending(); 2 -> memory.loadPeople(); 3 -> memory.loadWeek() }
        }
        selectSection = { name -> tabs.check(ids[when (name) { "pending" -> 1; "people" -> 2; "week" -> 3; else -> 0 }]) }
        val pad = ui.dp(12)
        val head = Column(requireContext()).apply { root.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0); header("Bộ nhớ", "Nhật ký, việc cần làm, người và tuần") }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(head.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ui.dp(18), pad, ui.dp(18), 0) })
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private lateinit var memory: MemoryViews
    private var selectSection: (String) -> Unit = {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectSection(activity?.intent?.getStringExtra(MainActivity.EXTRA_SECTION) ?: "day")
        activity?.intent?.removeExtra(MainActivity.EXTRA_SECTION)
        load()
        app.ambient.refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                app.ambient.status.collect { s ->
                    val c = s.counts
                    ambientStatus.text = buildString {
                        append(if (s.recording) "● ĐANG GHI · " else "Đã dừng · ").append(s.note)
                        if (s.recording) append("\nĐã ghi: ${s.capturedMs / 60000} phút ${(s.capturedMs / 1000) % 60} giây")
                        append("\nHàng đợi: ${c.pending} đoạn (${c.pendingMs / 1000} giây lời nói) · đã chép ${c.done} (${c.transcribedMs / 60000} phút) · lỗi ${c.failed}")
                        if (s.error.isNotEmpty()) append("\nLỗi gần nhất: ${s.error}")
                    }
                    ambientStatus.setTextColor(statusColor(if (s.error.isNotEmpty() && c.failed > 0) false else if (s.recording) true else null))
                }
            }
        }
    }

    private fun startAmbient(glasses: Boolean) {
        if (!app.settings.ambientSettings().consent) {
            AlertDialog.Builder(requireContext()).setTitle("Trước khi ghi âm môi trường")
                .setMessage("Bản ghi thu giọng bạn và những người xung quanh. Hãy báo cho người đi cùng và tôn trọng quy định về quyền riêng tư nơi bạn ở. " +
                    "Âm thanh nằm trên điện thoại và chỉ những đoạn có tiếng nói được gửi tới dịch vụ chép lời đã cấu hình (Deepgram/OpenAI). " +
                    "Âm thanh bị xóa sau khi chép lời (có thể đổi). Bạn có thể tạm dừng bất cứ lúc nào.")
                .setPositiveButton("Tôi đã hiểu và đồng ý") { _, _ ->
                    app.settings.saveAmbient(app.settings.ambientSettings().copy(consent = true)); startAmbient(glasses)
                }.setNegativeButton("Hủy", null).show()
            return
        }
        if (!glasses) (activity as? MainActivity)?.requestPermissionsIfNeeded()
        val provider = app.settings.ambientSettings().provider
        if (provider == org.turboio.customai.ambient.AmbientProvider.DEEPGRAM && !app.settings.hasDeepgramKey()) toast("Lưu ý: hãy nhập khóa Deepgram ở tab AI; âm thanh sẽ nằm trong hàng đợi đến lúc đó")
        (if (glasses) app.ambient.startGlasses() else app.ambient.startPhoneMic())?.let { toast(it) }
    }

    private fun load() {
        dayLabel.text = "Nhật ký · ${Days.label(day)}"
        viewLifecycleOwner.lifecycleScope.launch {
            val (entries, insight) = withContext(Dispatchers.IO) { app.journal.day(day) to app.journal.latestInsight(day) }
            insightText.text = insight?.text ?: if (entries.isEmpty()) "Không có ghi chép nào trong ngày này." else "Chưa tạo. Bấm \"Tạo\"."
            lastLens = insight?.let { org.turboio.customai.insights.DayInsight.parse(it.json)?.lens() } ?: ""
            status.text = "${entries.size} ghi chép" + (insight?.let { " · phân tích lúc ${Days.time(it.createdAt)} (${it.model})" } ?: "")
            render(entries, showDay = false)
        }
    }

    private fun render(entries: List<Entry>, showDay: Boolean) {
        timeline.removeAllViews()
        val c = Column(requireContext())
        if (entries.isEmpty()) c.hint("Không có ghi chép.")
        for (e in entries) {
            val head = (if (showDay) Days.label(e.day) + " " else "") + Days.time(e.tsStart) + " · " + e.source.label
            val body = if (e.kind == EntryKind.INTERACTION) "Tôi: ${e.text}\nAI: ${e.answer}" else e.text
            val tv = c.text("$head\n${body.take(1200)}", size = 13.5f)
            tv.setPadding(0, c.dp(6), 0, c.dp(6))
            tv.setOnLongClickListener { entryMenu(e); true }
        }
        c.hint("Giữ lâu một câu nói để cho biết ai đang nói hoặc để xóa nó.")
        timeline.addView(c.root)
    }

    private fun generate(force: Boolean) {
        generate.isEnabled = false
        status.text = "Đang tạo phân tích…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { app.insights.day(day, force) }
                insightText.text = r.text; lastLens = r.insight.lens()
                status.text = if (r.cached) "Không có gì mới từ lần tạo trước (bộ nhớ đệm)." else "✓ Đã tạo phân tích (${r.windows} khối)."
            } catch (e: Exception) {
                status.text = "✗ " + TurboApp.friendly(e)
            } finally { generate.isEnabled = true }
        }
    }

    private fun runSearch() {
        val q = search.text.toString().trim()
        if (q.length < 2) { toast("Hãy nhập ít nhất 2 chữ"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) { app.journal.search(q) }
            status.text = "${hits.size} kết quả cho \"$q\""
            render(hits, showDay = true)
        }
    }

    private fun export() {
        viewLifecycleOwner.lifecycleScope.launch {
            val md = withContext(Dispatchers.IO) { JournalExport.markdown(day, app.journal.day(day), app.journal.latestInsight(day)) }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, "Nhật ký ${Days.label(day)}").putExtra(Intent.EXTRA_TEXT, md), "Xuất nhật ký"))
        }
    }

    private fun entryMenu(e: Entry) {
        val labelled = e.kind == EntryKind.AMBIENT && runCatching { org.json.JSONObject(e.metaJson).optString("label").let { it.isNotEmpty() && it != "Você" } }.getOrDefault(false)
        if (!labelled) { confirmDelete(e); return }
        AlertDialog.Builder(requireContext()).setTitle(e.text.substringBefore(":").take(40))
            .setItems(arrayOf("Ai đang nói?", "Xóa câu nói này")) { _, which ->
                if (which == 0) memory.assignSpeaker(e) { load() } else confirmDelete(e)
            }.show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(requireContext()).setTitle("Xóa ghi chép?").setMessage(e.text.take(200))
            .setPositiveButton("Xóa") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { app.journal.delete(e.id) }; load() } }
            .setNegativeButton("Hủy", null).show()
    }

    private fun confirmDeleteDay() {
        AlertDialog.Builder(requireContext()).setTitle("Xóa ${Days.label(day)}?").setMessage("Xóa mọi ghi chép và phân tích của ngày này.")
            .setPositiveButton("Xóa") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { app.journal.deleteDay(day) }; load() } }
            .setNegativeButton("Hủy", null).show()
    }

    private fun toast(t: String) = Toast.makeText(requireContext(), t, Toast.LENGTH_SHORT).show()
}
