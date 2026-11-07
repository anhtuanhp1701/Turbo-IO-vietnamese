package org.turboio.customai.probe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Where the guided suite keeps its progress, so it survives the app being killed (step G5 requires that). */
interface SuiteStore { fun load(): String?; fun save(json: String) }

class SuiteStep(
    val id: String,
    val title: String,
    val instructions: String,
    /** Script shown while audio is arriving: (seconds since audio start, what to do). */
    val cues: List<Pair<Int, String>> = emptyList(),
    val start: (HardwareProbe) -> String?,
) {
    fun cue(audioSeconds: Int): String? = if (audioSeconds < 0) null else cues.lastOrNull { it.first <= audioSeconds }?.second
}

data class StepResult(val id: String, val done: Boolean, val verdict: String, val savedAt: String)

data class SuiteState(
    val active: Boolean = false,
    val index: Int = 0,
    val running: Boolean = false,
    val startedAt: Long = 0,
    val results: List<StepResult> = emptyList(),
) {
    val finished: Boolean get() = index >= TestSuite.STEPS.size
    val current: SuiteStep? get() = TestSuite.STEPS.getOrNull(index)
}

/**
 * Runs every hardware probe in a fixed order with instructions between them; the user only taps "start" for each
 * step and exports one package at the end. Each step is an ordinary bounded probe (same OFF guarantees).
 */
class TestSuite(private val probe: HardwareProbe, private val store: SuiteStore, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        val STEPS = listOf(
            SuiteStep("G1-G3", "LifeLog 40 giây theo kịch bản",
                "Hãy có người khác ở gần (hoặc một video có tiếng nói). Kịch bản bắt đầu khi âm thanh từ kính tới.",
                listOf(0 to "BẠN nói: đếm chậm từ 1 đến 10", 10 to "IM LẶNG hoàn toàn", 20 to "NGƯỜI KHÁC nói (bạn im lặng)",
                    30 to "BẠN và người kia nói xen kẽ")) { it.startLifeLog(40, realtimePage = true, countFromAudio = true) },
            SuiteStep("G7", "Bật LifeLog từ kính",
                "Bấm Bắt đầu và trong 90 giây, bật LifeLog từ menu của kính. Ghi lại những gì hiện trên kính.",
                listOf(0 to "Đã bật! Hãy nói vài câu")) { it.startLifeLogFromGlasses(90, 30) },
            SuiteStep("G8", "Các app gốc của kính (chỉ quan sát)",
                "Bấm Bắt đầu rồi trên kính lần lượt mở từng app, mỗi app khoảng 30 giây: Phụ đề (Subtitle), Trợ lý hội thoại, " +
                    "Máy nhắc chữ và Máy ghi âm. Trong mỗi app, nói vài câu. App chỉ ghi lại những gì kính gửi; không thay đổi gì.",
                listOf(0 to "Mở Phụ đề và nói", 35 to "Giờ đến Trợ lý hội thoại", 70 to "Giờ đến Máy nhắc chữ",
                    105 to "Giờ đến Máy ghi âm: ghi vài giây", 140 to "Đóng các app")) { it.startObserve(170) },
            SuiteStep("G14", "Máy ghi âm gốc (kênh 14)",
                "Bấm Bắt đầu và trong 60 giây, mở app Máy ghi âm của kính (không phải LifeLog) và ghi khoảng 20 giây; rồi dừng trên kính.",
                listOf(0 to "Đang ghi: hãy nói rồi dừng trên kính")) { it.startRecording14(120) },
            SuiteStep("G4", "LifeLog 5 phút thời gian thực",
                "Luôn đeo kính trên mặt. Trò chuyện bình thường, có ngắt nghỉ. Có thể để điện thoại sang một bên.",
                listOf(0 to "Trò chuyện bình thường, đeo kính trên mặt")) { it.startLifeLog(300, realtimePage = true, countFromAudio = true) },
            SuiteStep("G5", "Tắt sau khi buộc đóng app",
                "Bấm Bắt đầu. Khi thấy “âm thanh: 10 s”, hãy buộc đóng app (Cài đặt → Ứng dụng → Turbo IO → Buộc dừng). " +
                    "Mở lại app và kết nối kính: app phải tự tắt LifeLog và hoàn tất bước này.",
                listOf(0 to "Vui lòng chờ…", 10 to "NGAY BÂY GIỜ: buộc dừng app rồi mở lại")) { it.startLifeLog(600, realtimePage = true) },
        )
    }

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SuiteState> = _state

    init { probe.addFinishedListener { onProbeFinished(it) } }

    fun begin() = update(SuiteState(active = true, startedAt = clock()))

    fun startStep(): String? {
        val s = _state.value
        if (!s.active || s.running) return "Bộ kiểm tra không đang chờ bước nào"
        val step = s.current ?: return "Đã hoàn tất bộ kiểm tra"
        step.start(probe)?.let { return it }
        update(s.copy(running = true))
        return null
    }

    fun skip() {
        val s = _state.value
        val step = s.current ?: return
        if (!s.active || s.running) return
        update(s.copy(index = s.index + 1, results = s.results + StepResult(step.id, false, "pulado", "")))
    }

    fun end() = update(_state.value.copy(active = false, running = false))

    private fun onProbeFinished(p: ProbeState) {
        val s = _state.value
        if (!s.active || !s.running) return
        val step = s.current ?: return
        update(s.copy(index = s.index + 1, running = false, results = s.results + StepResult(step.id, true, p.verdict, p.savedAt)))
    }

    private fun update(s: SuiteState) {
        _state.value = s
        runCatching { store.save(encode(s)) }
    }

    private fun encode(s: SuiteState) = JSONObject().put("active", s.active).put("index", s.index).put("running", s.running)
        .put("startedAt", s.startedAt).put("results", JSONArray().apply {
            s.results.forEach { put(JSONObject().put("id", it.id).put("done", it.done).put("verdict", it.verdict).put("savedAt", it.savedAt)) }
        }).toString()

    private fun restore(): SuiteState = runCatching {
        val j = JSONObject(store.load() ?: return SuiteState())
        val r = j.getJSONArray("results")
        val index = j.getInt("index")
        // After a restart only G5 is still "running" (its OFF re-send on reconnect completes it); others are re-started.
        val running = j.getBoolean("running") && STEPS.getOrNull(index)?.id == "G5"
        SuiteState(j.getBoolean("active"), index, running, j.getLong("startedAt"),
            (0 until r.length()).map { r.getJSONObject(it).let { o -> StepResult(o.getString("id"), o.getBoolean("done"), o.getString("verdict"), o.getString("savedAt")) } })
    }.getOrDefault(SuiteState())
}
