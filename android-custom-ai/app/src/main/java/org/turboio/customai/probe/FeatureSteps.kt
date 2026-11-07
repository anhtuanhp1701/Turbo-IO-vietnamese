package org.turboio.customai.probe

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.turboio.customai.TurboApp
import org.turboio.customai.device.Teleprompter
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.DisplaySettings
import kotlin.coroutines.resume

/**
 * The feature test steps. Each one does the real action through the same code the app uses, and reports what the
 * phone saw; the user then says whether it really appeared on the lenses. Nothing here changes the firmware; settings
 * changed by a step (dashboard type, test card, test To-do item) are put back by the same step.
 */
object FeatureSteps {
    private fun ready(app: TurboApp): String? = if (app.session.isReady) null else "Chưa kết nối kính: hãy kết nối ở tab Kính rồi làm lại"

    private suspend fun show(app: TurboApp, text: String, settings: DisplaySettings): String =
        suspendCancellableCoroutine { cont -> app.display.show(text, settings) { e -> if (cont.isActive) cont.resume(e ?: "exibido") } }

    /** Log lines (already redacted) written since [since] whose text contains any of [words]. */
    private fun logSince(since: Long, vararg words: String): List<String> =
        DiagnosticLog.snapshot().filter { it.time >= since && words.any { w -> it.message.contains(w, ignoreCase = true) } }.map { it.message }

    fun all(app: TurboApp): List<FeatureStep> = listOf(
        FeatureStep("F1", "Chữ trên kính (3 định dạng)",
            "App hiện chữ thử ba lần: A (định dạng cũ, gửi lại mỗi 2 giây), B (phiên phụ đề của app " +
                "chính thức: 19/1 → 19/2 → chữ, bố cục cổ điển) và C (cùng phiên, bố cục tiêu đề). Ghi lại cái nào hiện trên kính và có ở lại màn hình không.",
            "Những chữ cái nào đã hiện (A, B, C)? Dấu tiếng Việt (ă, ơ, ữ, đ) có hiện đúng không? Ghi vào nhận xét.", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val base = app.settings.displaySettings().copy(fontSize = 2, position = "center", maxLines = 5, lingerSeconds = 6)
            val variants = listOf(Triple("A", "legacy", true), Triple("B", "session_classic", true), Triple("C", "session_title", true))
            val results = variants.map { (letter, format, resend) ->
                run.cue("Định dạng $letter: hãy nhìn vào kính")
                "$letter ($format${if (resend) ", repetido" else ", uma vez"}): " +
                    show(app, "$letter: xin chào! Tiếng Việt có dấu: ắ, ộ, ữ, đ.", base.copy(textFormat = format, resend = resend))
            }
            results.joinToString("\n") + "\nĐịnh dạng đang dùng trong app: ${app.settings.displaySettings().textFormat} (đổi ở tab AI)"
        },
        FeatureStep("F2", "Cỡ và vị trí chữ",
            "App hiện cùng một đoạn chữ với cỡ LỚN ở TRÊN, rồi cỡ NHỎ ở DƯỚI.",
            "Chữ có đổi cỡ và vị trí giữa hai lần hiển thị không?") { run ->
            ready(app)?.let { return@FeatureStep it }
            val t0 = System.currentTimeMillis()
            val base = app.settings.displaySettings().copy(lingerSeconds = 5)
            run.cue("Nhìn vào kính: chữ lớn ở trên")
            val big = show(app, "Chữ lớn ở trên", base.copy(fontSize = 3, position = "top", maxLines = 3))
            run.cue("Giờ: chữ nhỏ ở dưới")
            val small = show(app, "Chữ nhỏ ở dưới", base.copy(fontSize = 1, position = "bottom", maxLines = 5))
            val refused = logSince(t0, "recusado")
            "Lớn/trên: $big\nNhỏ/dưới: $small\n" +
                if (refused.isEmpty()) "Kính đã nhận cả hai bố cục" else "Bị từ chối (quay về mặc định):\n" + refused.joinToString("\n")
        },
        FeatureStep("F3", "Hỏi AI trên kính",
            "App hỏi nhà cung cấp AI của bạn “Thủ đô của Việt Nam là gì?” và hiện câu trả lời.",
            "Câu trả lời có hiện trên kính bằng tiếng Việt không?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val r = try { app.askAi("Thủ đô của Việt Nam là gì? Trả lời trong một câu.", record = false) }
                catch (e: Exception) { return@FeatureStep "AI lỗi: ${TurboApp.friendly(e)}" }
            "AI: ${r.provider.label}/${r.model} trong ${r.latencyMs} ms: “${r.text.take(120)}”\nHiển thị: " + show(app, r.text, app.settings.displaySettings())
        },
        FeatureStep("F4", "Trợ lý giọng nói (thao tác trên kính)",
            "Sau khi bấm Bắt đầu, hãy gọi trợ lý trên kính như bạn vẫn dùng (giữ lâu nút, cử chỉ hoặc “Hey RayNeo”) " +
                "và hỏi “Thủ đô của Pháp là gì?”. Trong 90 giây. Ghi vào nhận xét bạn đã dùng thao tác nào.",
            "Kính có nghe được câu hỏi và câu trả lời của AI có hiện trên kính không?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            voiceRound(app, run, "Gọi trợ lý trên kính rồi hỏi")
        },
        FeatureStep("F5", "“Hey RayNeo”",
            "Chỉ khi bạn đã bật “Hey RayNeo” (tab Kính). Nói “Hey RayNeo” rồi hỏi “Hai cộng hai bằng mấy?”. Trong 90 giây. Nếu chưa bật, bỏ qua.",
            "Kính có thức dậy khi nghe “Hey RayNeo” và câu trả lời của AI có hiện không?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            voiceRound(app, run, "Nói “Hey RayNeo” rồi hỏi")
        },
        FeatureStep("F6", "Cảnh báo dạng thông báo gốc",
            "App gửi một thẻ thông báo gốc của kính (giống lời nhắc).",
            "Thẻ thông báo “Thử Turbo IO” có hiện trên kính không?") { _ ->
            ready(app) ?: "Thẻ: " + app.nativeNotifier.show("Thử Turbo IO", "Cảnh báo gốc thử nghiệm của Turbo IO").label
        },
        FeatureStep("F7", "Thông báo điện thoại trên kính",
            "App kiểm tra quyền đọc thông báo, gửi một thẻ “WhatsApp” giả lập, rồi chờ 60 giây một thông báo thật " +
                "từ app đã chọn (nhờ ai đó nhắn cho bạn, hoặc tự gửi từ máy tính).",
            "Thẻ giả lập có hiện không? Còn tin nhắn thật (nếu có)?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val access = app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
            val apps = app.mirrorAppsInEffect()
            val simulated = app.nativeNotifier.show("Lan (thử)", "Tin nhắn giả lập của bài thử Turbo IO", "WhatsApp").label
            val before = app.mirroredCards
            if (access && app.settings.mirrorOn && apps.isNotEmpty()) run.countdown(60, "Đang chờ một thông báo thật")
            val real = app.mirroredCards - before
            "Quyền đọc thông báo: ${if (access) "liberado" else "NÃO liberado"} · envio ligado: ${app.settings.mirrorOn} · apps em uso: ${apps.size}${if (app.settings.mirrorAppsChosen) "" else " (gợi ý)"}\n" +
                "Thẻ giả lập: $simulated\nThông báo thật đã chuyển: $real" +
                if (!access || !app.settings.mirrorOn || apps.isEmpty()) " (không chờ: cần cấp quyền, bật gửi thông báo hoặc chọn app)" else ""
        },
        FeatureStep("F8", "Chỉ đường Google Maps",
            "App hiện một dòng chỉ đường giả lập. Sau đó chờ 60 giây: nếu được, hãy bắt đầu một lộ trình đi bộ trên Google Maps.",
            "Dòng “↱ 200 m · Đường Thử” có hiện không? Khi dùng Maps, các bước rẽ có hiện không?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            val simulated = show(app, "↱ 200 m · Đường Thử", app.settings.displaySettings().copy(lingerSeconds = 5, pageSeconds = 5))
            val before = app.navLines
            if (app.settings.navOn) run.countdown(60, "Bắt đầu một lộ trình trên Google Maps (tùy chọn)")
            "Giả lập: $simulated\nBật chỉ đường: ${app.settings.navOn} · dòng thật từ Maps: ${app.navLines - before}"
        },
        FeatureStep("F9", "Thời tiết trên màn hình chính",
            "App lấy thời tiết thành phố của bạn (hoặc Hà Nội nếu chưa đặt) và gửi lên màn hình chính của kính.",
            "Thời tiết (nhiệt độ và biểu tượng) có hiện đúng trên màn hình chính của kính không?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val city = app.settings.weatherCity.ifBlank { "Hà Nội" }
            val w = try { withContext(Dispatchers.IO) { org.turboio.customai.device.OpenMeteo.now(city) } }
                catch (e: Exception) { return@FeatureStep "Open-Meteo lỗi: ${e.message ?: e.javaClass.simpleName}" }
            app.weather.send(w)
        },
        FeatureStep("F10", "To-do của kính",
            "App gửi mục “Thử Turbo IO · có thể đánh dấu” vào To-do của kính. Mở To-do trên kính và đánh dấu xong (trong 90 giây). " +
                "Cuối cùng app đánh dấu mục là xong để không còn sót lại.",
            "Mục có hiện trong To-do của kính không? App có nhận ra khi bạn đánh dấu trên kính không?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val id = app.glassesTodo.testWireId()
            app.glassesTodo.sendTest(id, "Thử Turbo IO · có thể đánh dấu", 0)?.let { return@FeatureStep "Gửi thất bại: $it" }
            var marked = -1
            app.glassesTodo.wireListener = { w, st -> if (w == id) marked = st }
            try {
                for (left in 90 downTo 1) { if (marked == 1) break; run.cue("Mở To-do trên kính và đánh dấu mục · còn $left giây"); delay(1000) }
            } finally { app.glassesTodo.wireListener = null }
            val cleanup = if (marked == 1) "đã đánh dấu trên kính" else app.glassesTodo.sendTest(id, "Thử Turbo IO · có thể đánh dấu", 1) ?: "app đã đánh dấu xong"
            "Đã gửi mục. Đánh dấu trên kính: ${if (marked == 1) "recebida ✓" else "não recebida"}. Dọn dẹp: $cleanup"
        },
        FeatureStep("F11", "Phản ứng khi mở app trên kính",
            "Mở To-do từ menu của kính trong vòng 60 giây. App phải nhận ra và đồng bộ các việc.",
            "Bạn đã mở To-do chưa? (kết quả của app cho biết nó có nhận ra không)", timeoutSeconds = 90) { run ->
            ready(app)?.let { return@FeatureStep it }
            run.cue("Mở To-do trên kính")
            val seen = ArrayList<String>()
            val opened = withTimeoutOrNull(60_000) { app.openedOnGlasses.first { seen.add(it); it == "com.rayneo.liteos.todo" } }
            if (opened != null) "Đã nhận ra: To-do được mở trên kính ✓" else "Không nhận ra trong 60 giây. Các app đã thấy: ${seen.joinToString().ifEmpty { "không có" }}"
        },
        FeatureStep("F12", "Máy nhắc chữ từ điện thoại",
            "App gửi một kịch bản ngắn tới máy nhắc chữ của kính và khi sẵn sàng sẽ tự bắt đầu, tạm dừng, tiếp tục, đổi tốc độ rồi thoát.",
            "Kịch bản có hiện và cuộn trên kính không? Tạm dừng/tiếp tục/tốc độ có chạy không? Có thoát ở cuối không?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            teleprompter(app, run)
        },
        FeatureStep("F13", "Máy nhắc chữ từ menu của kính",
            "Mở Máy nhắc chữ từ menu của kính: phải thấy các kịch bản đã lưu trên điện thoại (kể cả của bước F12). " +
                "Chọn một kịch bản và thử bắt đầu trên kính. Trong 60 giây.",
            "Danh sách có hiện không? Kịch bản đã chọn có mở và cuộn không? (ghi vào phần nhận xét)", timeoutSeconds = 100) { run ->
            ready(app)?.let { return@FeatureStep it }
            val saved = app.scripts.list().size
            run.countdown(60, "Mở Máy nhắc chữ trong menu kính ($saved kịch bản đã lưu)")
            val t = app.teleprompter.state.value
            "Kịch bản đã lưu trên điện thoại: $saved\nMáy nhắc chữ trong app: ${t.phase.label} · ${t.note}"
        },
        FeatureStep("F14", "Bảng điều khiển: đọc",
            "App đọc cấu hình bảng điều khiển của kính (không thay đổi gì).",
            "Danh sách thẻ bên dưới có khớp với bảng điều khiển của bạn không?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val (snap, why) = app.dashboard.read()
            snap?.describe() ?: why
        },
        FeatureStep("F15", "Bảng điều khiển: thẻ việc cần làm",
            "App cài thẻ việc cần làm lên bảng điều khiển, chờ 25 giây để bạn xem rồi gỡ thẻ.",
            "Thẻ có hiện trên bảng rồi biến mất không? Các thẻ khác có giữ nguyên không?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val shown = app.refreshDashboardCard(force = true)
            run.countdown(25, "Xem bảng điều khiển của kính: phải có thẻ Turbo IO")
            val removed = if (app.settings.dashboardCard) "giữ lại (đang bật tự động cập nhật)" else app.dashboard.removeCard()
            "Cài đặt: $shown\nGỡ: $removed"
        },
        FeatureStep("F16", "Bảng điều khiển: kiểu đơn giản/đầy đủ",
            "App đổi kiểu bảng điều khiển, chờ 25 giây để bạn xem rồi đổi lại kiểu ban đầu.",
            "Bảng điều khiển có đổi giao diện rồi trở lại bình thường không?", timeoutSeconds = 150) { run ->
            ready(app)?.let { return@FeatureStep it }
            val (snap, why) = app.dashboard.read()
            val current = snap?.simple ?: return@FeatureStep "Đọc: ${snap?.let { "os óculos não informam o tipo (simple_mode)" } ?: why}"
            val changed = app.dashboard.setSimpleMode(!current) { if (app.settings.dashboardSimpleOriginal == null) app.settings.dashboardSimpleOriginal = it }
            try {
                run.countdown(25, "Xem bảng điều khiển: phải đang ở kiểu ${if (current) "đầy đủ" else "simples"}")
            } finally {
                // Runs even when the step is stopped: the dashboard always goes back to the type it had.
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { app.dashboard.setSimpleMode(current) }
                if (back.startsWith("Bảng điều khiển giờ") || back.startsWith("Bảng điều khiển đã")) app.settings.dashboardSimpleOriginal = null
                DiagnosticLog.i("PAINEL", "F16 volta: $back")
            }
            "Kiểu đọc được: ${if (current) "simples" else "đầy đủ"}\nĐổi: $changed\nTrả về kiểu ban đầu: xem log (PAINEL)"
        },
        FeatureStep("F17", "Gật và lắc đầu",
            "Hai thẻ sẽ hiện trên kính. Thẻ đầu tiên: GẬT đầu (có). Thẻ thứ hai: LẮC đầu (không). Mỗi thẻ tối đa 40 giây.",
            "Các thẻ có hiện và cử chỉ có được nhận đúng như kết quả không?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            run.cue("GẬT đầu (có)")
            val yes = app.suggestions.ask("Thử: gật để chọn CÓ", "Gật đầu", "Thử", 40_000)
            run.cue("Giờ hãy LẮC đầu (không)")
            val no = app.suggestions.ask("Thử: lắc để chọn KHÔNG", "Lắc đầu", "Thử", 40_000)
            "Thẻ thứ nhất: ${yes.label} (mong đợi: gật)\nThẻ thứ hai: ${no.label} (mong đợi: lắc)"
        },
        FeatureStep("F18", "Cài đặt màn hình (đọc)",
            "App đọc độ cao, khoảng cách, chạm hai lần và từ đánh thức (không thay đổi gì).",
            "Các giá trị bên dưới có khớp với app chính thức / những gì bạn thấy không?") { _ ->
            ready(app)?.let { return@FeatureStep it }
            val error = suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.read { e -> if (cont.isActive) cont.resume(e) } }
            val c = app.glassesSettings.state.value.config
            (error?.let { "Đọc: $it\n" } ?: "") +
                "Độ cao ${c.height ?: "?"} · khoảng cách ${c.distance ?: "?"} · chạm hai lần ${c.crownDouble ?: "?"} · giữ lâu ${c.crownLongPress ?: "?"} · từ đánh thức ${c.wakeup ?: "?"}" +
                "\nGiá trị đọc được: ${app.glassesSettings.state.value.details}"
        },
        FeatureStep("F20", "Ngẩng đầu: góc",
            "App đổi góc “ngẩng đầu để sáng màn hình” thành 35°, cho bạn 40 giây để thử (để màn hình tắt rồi ngẩng đầu) " +
                "rồi trả lại góc và trạng thái ban đầu.",
            "Với 35° bạn có phải ngẩng cao hơn trước không? Màn hình có sáng không? (ghi vào nhận xét)", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            suspend fun wake(headup: Boolean? = null, degree: Int? = null) =
                suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.setWake(headup, degree) { e -> if (cont.isActive) cont.resume(e) } }
            val readError = suspendCancellableCoroutine<String?> { cont -> app.glassesSettings.read { e -> if (cont.isActive) cont.resume(e) } }
            val before = app.glassesSettings.state.value.config
            if (!before.hasWake) return@FeatureStep "Kính không cho biết wakeupConfig${readError?.let { " ($it)" } ?: ""}\nValores lidos: ${app.glassesSettings.state.value.details}"
            val test = if (before.wakeDegree == 35) 25 else 35
            try {
                val on = if (before.wakeHeadup == 1) "đã bật sẵn" else wake(headup = true) ?: "app đã bật"
                val change = wake(degree = test) ?: "đã áp dụng góc $test° (đọc lại)"
                run.countdown(40, "Góc $test°: để màn hình tắt rồi ngẩng đầu")
                return@FeatureStep "Trước: ${if (before.wakeHeadup == 1) "đang bật" else "đã tắt"}, ${before.wakeDegree}°\nBật: $on\nĐổi: $change"
            } finally {
                // Runs even when the step is stopped: the angle and switch go back to what they were.
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    wake(headup = before.wakeHeadup == 1, degree = before.wakeDegree) ?: "đã khôi phục (${before.wakeDegree}°)"
                }
                DiagnosticLog.i("AJUSTES", "F20 volta: $back")
            }
        },
        FeatureStep("F19", "Nhật ký: ghi bằng kính",
            "App ghi 40 giây bằng micro của kính vào nhật ký. Hãy trò chuyện hoặc đọc to. Bản chép lời sẽ vào Nhật ký sau vài phút. " +
                "Trước đó, hãy đồng ý thông báo quyền riêng tư ở Nhật ký → Ghi âm môi trường (một lần).",
            "Trong Nhật ký, cuộc trò chuyện có được chép lời không (kiểm tra sau vài phút)?", timeoutSeconds = 120) { run ->
            ready(app)?.let { return@FeatureStep it }
            app.ambient.startGlasses()?.let { return@FeatureStep "Không bắt đầu được: $it" }
            try { run.countdown(40, "Đang ghi bằng kính: hãy trò chuyện") } finally { app.ambient.stop("kết thúc bài thử") }
            val s = app.ambient.status.value
            "Âm thanh đã ghi: ${s.capturedMs / 1000} giây · đoạn: ${s.segments}\n${s.note}${if (s.error.isNotEmpty()) "\nErro: ${s.error}" else ""}"
        },
    )

    private suspend fun voiceRound(app: TurboApp, run: StepRun, what: String): String {
        val before = app.voice.state.value.rounds
        val done = withTimeoutOrNull(90_000) {
            launch { for (left in 90 downTo 1) { run.cue("$what · $left s"); delay(1000) } }.let { ticker ->
                try { app.voice.state.first { it.rounds > before } } finally { ticker.cancel() }
            }
        }
        val v = app.voice.state.value
        return (if (done == null) "Không có lượt giọng nói nào hoàn tất trong 90 giây\n" else "Đã hoàn tất một lượt ✓\n") +
            "Trạng thái: ${v.phase} · ${v.note}\nChép lời: ${v.transcript.ifEmpty { "—" }}\nTrả lời: ${v.answer.take(160).ifEmpty { "—" }}" +
            "\nÂm thanh từ kính: ${v.audioPackets} gói / ${v.audioMs} ms" + if (v.lastError.isNotEmpty()) "\nLỗi gần nhất: ${v.lastError}" else ""
    }

    private suspend fun teleprompter(app: TurboApp, run: StepRun): String {
        val t = app.teleprompter
        val history = ArrayList<String>()
        val watcher = app.scope.launch { t.state.collect { s -> val line = "${s.phase.label}: ${s.note}"; if (history.lastOrNull() != line) history.add(line) } }
        try {
            if (t.active) t.stop()
            val script = "Thử máy nhắc chữ Turbo IO. Đây là dòng đầu tiên của kịch bản. Dòng thứ hai tiếp tục văn bản. " +
                "Bây giờ chữ sẽ cuộn chậm trên kính. Kết thúc bài thử."
            t.prepare(script, 120)?.let { return "Chuẩn bị: $it" }
            run.cue("Đang gửi kịch bản lên kính")
            val ready = withTimeoutOrNull(60_000) { t.state.first { it.phase == Teleprompter.Phase.READY || it.phase == Teleprompter.Phase.ERROR || it.phase == Teleprompter.Phase.IDLE } }
            if (ready?.phase != Teleprompter.Phase.READY) return "Kịch bản chưa sẵn sàng: ${ready?.note ?: "sem resposta em 60 s"}\n\nTrình tự:\n" + history.joinToString("\n")
            suspend fun step(type: Int, label: String, seconds: Int, speed: Int = t.state.value.speed) {
                t.control(type, speed)?.let { history.add("✗ $label: $it"); return }
                withTimeoutOrNull(10_000) { t.state.first { it.phase != Teleprompter.Phase.CONTROLLING } }
                run.countdown(seconds, label)
            }
            step(3, "Đang cuộn", 8)
            step(4, "Đã tạm dừng", 4)
            step(5, "Đang tiếp tục", 4)
            step(7, "Tăng tốc độ", 4, 200)
            t.stop()
            withTimeoutOrNull(12_000) { t.state.first { it.phase == Teleprompter.Phase.IDLE } }
            return "Trạng thái cuối: ${t.state.value.phase.label}\n\nTrình tự:\n" + history.joinToString("\n")
        } finally {
            watcher.cancel()
            if (t.active) t.stop()
        }
    }
}
