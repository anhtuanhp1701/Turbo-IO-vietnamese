package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.R
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home: glasses status, shortcut tiles for every feature, today's pending items and the "ask the AI" bar. */
class HomeFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var frame: FrameLayout
    private var today: Pair<String, List<String>> = "Hôm nay" to listOf("Đang tải…")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        FrameLayout(requireContext()).also { frame = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { today = withContext(Dispatchers.IO) { loadToday() }; render() }
                // Only what the screen shows: the session status also changes on every received frame.
                combine(
                    app.status.map { Triple(it.phase, it.battery, it.deviceName + it.glassesVersions) }.distinctUntilChanged(),
                    app.ambient.status.map { it.recording }.distinctUntilChanged(),
                    app.teleprompter.state.map { it.phase }.distinctUntilChanged(),
                    app.glassesSettings.state.map { it.config }.distinctUntilChanged(),
                ) { _, _, _, _ -> }.collect { render() }
            }
        }
    }

    private fun main() = activity as? MainActivity

    private fun render() {
        if (!isAdded) return
        val s = app.status.value
        val connected = when (s.phase) { Phase.READY -> true; Phase.IDLE, Phase.FAILED -> false; else -> null }
        val firmware = s.glassesVersions.split("·").firstOrNull { it.contains("1.") }?.substringAfter("=")?.trim()?.let { "Strix OS $it" }
            ?: if (connected == true) "Firmware gốc" else if (app.session.hasSaved()) "Đã lưu kính · bấm Kết nối" else "Chưa lưu kính nào"
        val model = HomeModel(
            name = s.deviceName.ifBlank { "RayNeo iO" }.take(24),
            connected = connected, connection = ConnectionFragment.phaseLabel(s.phase),
            battery = s.battery.takeIf { it in 0..100 && connected == true },
            firmware = firmware, tiles = tiles(connected == true),
            todayTitle = today.first, todayLines = today.second,
        )
        val scrollY = (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.scrollY ?: 0
        frame.removeAllViews()
        frame.addView(HomeView.build(requireContext(), model, HomeActions(
            connect = { if (!app.connectSaved()) main()?.go(R.id.nav_glasses) },
            glasses = { main()?.go(R.id.nav_glasses) },
            today = { main()?.go(R.id.nav_memory) },
            ask = { main()?.open(TestFragment()) },
        )))
        (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.post { (frame.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.scrollTo(0, scrollY) }
    }

    private fun tiles(connected: Boolean): List<Tile> {
        val st = app.settings
        val voiceOn = st.voiceSettings().enabled
        val engine = st.sttConfig().engine.label.substringAfter("→ ", "").substringBefore(" (").ifEmpty { "micro điện thoại" }
        val rec = app.ambient.status.value.recording
        val tp = app.teleprompter.state.value
        val mem = st.memorySettings()
        val wake = app.glassesSettings.state.value.config
        val features = app.features.state.value
        return listOf(
            Tile(R.drawable.ic_mic, "Trợ lý giọng nói", if (voiceOn) "Bật · $engine" else "Đã tắt", voiceOn) {
                st.setVoiceEnabled(!voiceOn); render()
            },
            Tile(R.drawable.ic_chat, "Phiên dịch 2 chiều", if (app.interpreter.running) "Đang dịch" else "Bắt đầu", app.interpreter.running) {
                main()?.open(InterpreterFragment())
            },
            Tile(R.drawable.ic_record, "Ghi lại ngày", if (rec) "Đang ghi" else "Đã dừng", rec) { main()?.go(R.id.nav_memory) },
            Tile(R.drawable.ic_prompter, "Máy nhắc chữ", if (app.teleprompter.active) tp.phase.label.replaceFirstChar { it.uppercase() } else "Gửi kịch bản",
                app.teleprompter.active) { main()?.open(TestFragment()) },
            Tile(R.drawable.ic_bell, "Thông báo", if (st.mirrorOn) "Bật" + if (st.navOn) " · Maps" else "" else "Đã tắt", st.mirrorOn) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_todo, "To-do trên kính", if (mem.enabled && mem.glassesTodo) "Bật" else "Đã tắt", mem.enabled && mem.glassesTodo) {
                st.saveMemory(mem.copy(glassesTodo = !mem.glassesTodo)); app.memoryChanged(); render()
            },
            Tile(R.drawable.ic_lookup, "Ngẩng đầu",
                when (wake.wakeHeadup) { 1 -> "Bật · ${wake.wakeDegree}°"; 0 -> "Đã tắt"; else -> if (connected) "Đọc cài đặt" else "—" },
                wake.wakeHeadup == 1) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_weather, "Thời tiết", if (st.weatherOn) st.weatherCity.ifBlank { "Bật" } else "Đã tắt", st.weatherOn) { main()?.go(R.id.nav_glasses) },
            Tile(R.drawable.ic_flask, "Bộ kiểm tra", if (features.active) "${features.results.size}/${app.features.steps.size}" else "Thử tất cả",
                features.active) { main()?.open(DiagnosticsFragment()) },
        )
    }

    private fun loadToday(): Pair<String, List<String>> = runCatching {
        if (!app.settings.memorySettings().enabled) return "Bộ nhớ đang tắt" to listOf("Bật ở Bộ nhớ → Việc cần làm → Cài đặt.")
        val open = app.memoryStore.openItems(50)
        if (open.isEmpty()) return "Không có việc nào" to listOf("Bộ nhớ tạo việc cần làm từ các cuộc trò chuyện của bạn.")
        val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val lines = open.take(3).map { i ->
            i.text.take(60) + (i.dueAt?.let { " · " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(fmt) } ?: "")
        }
        (if (open.size == 1) "1 việc" else "${open.size} việc") to lines
    }.getOrElse { "Hôm nay" to listOf("Không đọc được bộ nhớ") }
}
